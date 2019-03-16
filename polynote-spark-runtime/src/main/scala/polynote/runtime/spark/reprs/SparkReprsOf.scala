package polynote.runtime.spark.reprs

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream}
import java.nio.ByteBuffer

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.{types => sparkTypes}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.storage.StorageLevel
import polynote.runtime._

trait SparkReprsOf[A] extends ReprsOf[A]

object SparkReprsOf {

  private def dataTypeAndEncoder(schema: sparkTypes.StructType): (StructType, (DataOutput, InternalRow) => Unit) = {
    val (fieldTypes, fieldEncoders) = schema.fields.zipWithIndex.flatMap {
      case (sparkTypes.StructField(name, dataType, nullable, _), index) =>
        Option(dataType).collect[(DataType, (DataOutput, InternalRow) => Unit)] {
          case sparkTypes.ByteType    => ByteType -> ((out, row) => DataEncoder.byte.encode(out, row.getByte(index)))
          case sparkTypes.BooleanType => BoolType -> ((out, row) => DataEncoder.boolean.encode(out, row.getBoolean(index)))
          case sparkTypes.ShortType   => ShortType -> ((out, row) => DataEncoder.short.encode(out, row.getShort(index)))
          case sparkTypes.IntegerType => IntType -> ((out, row) => DataEncoder.int.encode(out, row.getInt(index)))
          case sparkTypes.LongType    => LongType ->((out, row) => DataEncoder.long.encode(out, row.getLong(index)))
          case sparkTypes.FloatType   => FloatType -> ((out, row) => DataEncoder.float.encode(out, row.getFloat(index)))
          case sparkTypes.DoubleType  => DoubleType -> ((out, row) => DataEncoder.double.encode(out, row.getDouble(index)))
          case sparkTypes.BinaryType  => BinaryType -> ((out, row) => DataEncoder.byteArray.encode(out, row.getBinary(index)))
          case sparkTypes.StringType  => StringType -> ((out, row) => DataEncoder.string.encode(out, row.getString(index)))
          case struct @ sparkTypes.StructType(_) =>
            val (structType, encode) = dataTypeAndEncoder(struct)
            structType -> ((out, row) => encode(out, row.getStruct(index, struct.fields.length)))
        }.map {
          case (dt, enc) => StructField(name, dt) -> enc
        }
    }.unzip

    StructType(fieldTypes.toList) -> {
      (out, row) =>
        fieldEncoders.foreach(_.apply(out, row))
    }
  }

  private[polynote] class FixedSizeDataFrameDecoder(structType: StructType, encode: (DataOutput, InternalRow) => Unit) extends (InternalRow => Array[Byte]) with Serializable {
    assert(structType.size >= 0)
    override def apply(row: InternalRow): Array[Byte] = {
        // TODO: share/reuse this buffer? Any reason to be threadsafe?
      val arr = new Array[Byte](structType.size)
      val buf = ByteBuffer.wrap(arr)
      encode(new DataEncoder.BufferOutput(buf), row)
      arr
    }
  }

  private[polynote] class VariableSizeDataFrameDecoder(structType: StructType, encode: (DataOutput, InternalRow) => Unit) extends (InternalRow => Array[Byte]) with Serializable {
    override def apply(row: InternalRow): Array[Byte] = {
      val out = new ByteArrayOutputStream()
      try {
        encode(new DataOutputStream(out), row)
        out.toByteArray
      } finally {
        out.close()
      }
    }
  }


  private[polynote] class DataFrameHandle(
    val handle: Int,
    dataFrame: DataFrame
  ) extends StreamingDataRepr.Handle with Serializable {

    private val originalStorage = dataFrame.storageLevel
    private val (structType, encode) = dataTypeAndEncoder(dataFrame.schema)


    val dataType: DataType = structType
    val knownSize: Option[Int] = None

    // TODO: It might be nice to iterate instead of collect, but maybe in a better way than toLocalIterator...
    private lazy val collectedData = {
      val rowToBytes: InternalRow => Array[Byte] = if (structType.size >= 0) {
        new FixedSizeDataFrameDecoder(structType, encode)
      } else {
        new VariableSizeDataFrameDecoder(structType, encode)
      }

      dataFrame.limit(1000000).queryExecution.toRdd.map(rowToBytes).collect()
    }

    def iterator: Iterator[ByteBuffer] = collectedData.iterator.map(ByteBuffer.wrap)


    def modify(ops: List[TableOp]): Either[Throwable, Int => StreamingDataRepr.Handle] = {
      import org.apache.spark.sql.functions, functions.{when, col, sum, lit, struct, count, approx_count_distinct, avg}
      import org.apache.spark.sql.Column
      import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
      import org.apache.spark.sql.catalyst.expressions.aggregate.ApproximatePercentile
      import org.apache.spark.sql.catalyst.expressions.Literal


      @inline def tryEither[T](thunk: => T): Either[Throwable, T] = try Right(thunk) catch {
        case err: Throwable => Left(err)
      }

      def toAggregate(strings: (String, String)): (List[Column], Option[DataFrame => DataFrame]) = strings match {
        case (name, "quartiles") =>
          val quartilesAgg = new Column(new ApproximatePercentile(UnresolvedAttribute.quotedString(name), Literal(Array(0.0, 0.25, 0.5, 0.75, 1.0))).toAggregateExpression())
            .as(s"${name}_quartiles_array")
          val quartilesCol = col(s"${name}_quartiles_array")
          val meanAgg = functions.avg(name) as s"${name}_mean"
          val meanCol = col(s"${name}_mean")
          val post = (df: DataFrame) => df.withColumn(
            s"quartiles($name)",
            struct(quartilesCol(0) as "min", quartilesCol(1) as "q1", quartilesCol(2) as "median", meanCol as "mean", quartilesCol(3) as "q3", quartilesCol(4) as "max")
          ).drop(quartilesCol).drop(meanCol)
          List(quartilesAgg, meanAgg) -> Some(post)

        case (name, "sum") => List(sum(col(name)) as s"sum($name)") -> None
        case (name, "count") => List(count(col(name)) as s"count($name)") -> None
        case (name, "approx_count_distinct") => List(approx_count_distinct(col(name)) as s"approx_count_distinct($name)") -> None
        case (name, "mean") => List(avg(col(name)) as s"mean($name)") -> None



        case (_, op) => throw new UnsupportedOperationException(op)
      }

      ops.foldLeft(tryEither(dataFrame)) {
        (dfOrErr, op) => dfOrErr.right.flatMap {
          df => op match {
            case GroupAgg(cols, aggs) => tryEither {
              val (aggCols, postFns) = aggs.map(toAggregate).unzip match {
                case (aggColss, postFns) => aggColss.flatten -> postFns.flatten
              }
              val post = postFns.foldLeft(identity[DataFrame] _)(_ andThen _)
              post(df.groupBy(cols.head, cols.tail: _*).agg(aggCols.head, aggCols.tail: _*))
            }

            case QuantileBin(column, binCount, err) => tryEither {
              val probabilities = (BigDecimal(0.0) to BigDecimal(1.0) by (BigDecimal(1.0) / binCount)).map(_.doubleValue).toArray
              val quantiles = df.stat.approxQuantile(column, probabilities, err)
              val c = col(column)
              val cases = (1 until quantiles.length - 1).foldLeft(when(c < quantiles(1), quantiles.head)) {
                (accum, index) => accum.when(c >= quantiles(index) && c < quantiles(index + 1), quantiles(index))
              }.otherwise(quantiles.last)
              df.withColumn(s"${column}_quantized", cases)
            }

            case Select(columns) => tryEither(df.select(columns.head, columns.tail: _*))
          }
        }
      }.right.map {
        modifiedDataFrame => new DataFrameHandle(_, modifiedDataFrame)
      }
    }

    override def release(): Unit = {
      if (originalStorage == StorageLevel.NONE) {
        dataFrame.unpersist(false)
      } else {
        try {
          dataFrame.persist(originalStorage)
        } catch {
          case err: Throwable => dataFrame.unpersist(false)
        }
      }
      super.release()
    }
  }

  import org.apache.spark.sql.{Dataset, DataFrame}

  def instance[T](reprs: T => Array[ValueRepr]): SparkReprsOf[T] = new SparkReprsOf[T] {
    def apply(value: T): Array[ValueRepr] = reprs(value)
  }

  implicit val dataFrame: SparkReprsOf[DataFrame] = {
    instance {
      df => Array(StreamingDataRepr.fromHandle(new DataFrameHandle(_, df)))
    }
  }

}
