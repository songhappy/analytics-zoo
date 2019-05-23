/*
 * Copyright 2018 Analytics Zoo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.zoo.examples.recommendation

import java.nio.file.Paths

import com.intel.analytics.bigdl.dataset.{Sample, TensorSample}
import com.intel.analytics.bigdl.optim._
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.utils.T
import com.intel.analytics.zoo.common.NNContext
import com.intel.analytics.zoo.models.recommendation.SessionRecommender
import com.intel.analytics.zoo.pipeline.api.keras.objectives.SparseCategoricalCrossEntropy
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SQLContext}
import scopt.OptionParser

import scala.collection.mutable

case class SessionParams(
                          maxLength: Int = 5,
                          maxEpoch: Int = 10,
                          batchSize: Int = 1280,
                          embedOutDim: Int = 20,
                          learningRate: Double = 1e-3,
                          learningRateDecay: Double = 1e-6,
                          inputDir: String = "/Users/guoqiong/intelWork/projects/officeDepot/sampleData/",
                          outputDir: String = "./model/",
                          fileName: String = "atcHistory.json",
                          modelName: String = "sessionRecommender"
                        )

object SessionRecExp {

  val currentDir: String = Paths.get(".").toAbsolutePath + "/"

  def main(args: Array[String]): Unit = {

    val defaultParams = SessionParams()

    val parser = new OptionParser[SessionParams]("SessionRecExample") {
      opt[String]("inputDir")
        .text(s"inputDir")
        .action((x, c) => c.copy(inputDir = x))
      opt[String]("outputDir")
        .text(s"inputDir")
        .action((x, c) => c.copy(outputDir = x))
      opt[Int]('b', "batchSize")
        .text(s"batchSize")
        .action((x, c) => c.copy(batchSize = x))
    }

    parser.parse(args, defaultParams).map {
      params =>
        run(params)
    } getOrElse {
      System.exit(1)
    }
  }

  def run(params: SessionParams): Unit = {

    Logger.getLogger("org").setLevel(Level.WARN)

    // construct BigDL session
    val conf = new SparkConf()
      .setAppName("SessionRecExp")
      .setMaster("local[*]")

    val sc = NNContext.initNNContext(conf)
    val sqlContext = SQLContext.getOrCreate(sc)

    val (sessionDF, itemCount) = loadPublicData(sqlContext, params)
    val trainSample = assemblyFeature(sqlContext, sessionDF, itemCount, params.maxLength)
    val Array(trainRdd, testRdd) = trainSample.randomSplit(Array(0.8, 0.2), 100)

    val model = SessionRecommender[Float](
      itemCount = itemCount,
      itemEmbed = params.embedOutDim,
      mlpHiddenLayers = Array(20, 10),
      seqLength = 5,
      includeHistory = true)


    val optimMethod = new RMSprop[Float](
      learningRate = params.learningRate,
      learningRateDecay = params.learningRateDecay)

    model.compile(
      optimizer = optimMethod,
      loss = new SparseCategoricalCrossEntropy[Float](zeroBasedLabel = false),
      metrics = List(new Top5Accuracy[Float]()))

    model.fit(trainRdd, batchSize = 1024, validationData = testRdd)


    model.saveModule(params.inputDir + params.modelName, null, overWrite = true)
    println("Model has been saved")

  }

  //  Load data using spark session interface
  def loadPublicData(sqlContext: SQLContext, params: SessionParams): (DataFrame, Int) = {

    val sessionDF = sqlContext.read.options(Map("header" -> "false", "delimiter" -> ",")).json(params.inputDir + params.fileName)
    sessionDF.show(10, false)
    val atcMax = sessionDF.rdd.map(_.getAs[mutable.WrappedArray[Double]]("ATC_SEQ").max).collect().max.toInt
    val purMax = sessionDF.rdd.map(_.getAs[mutable.WrappedArray[Double]]("PURCH_HIST").max).collect().max.toInt
    val itemCount = Math.max(atcMax, purMax)
    (sessionDF, itemCount)
  }

  def assemblyFeature(
                       sqlContext: SQLContext,
                       sessionDF: DataFrame,
                       itemCount: Int,
                       maxLength: Int
                     ): RDD[Sample[Float]] = {

    // prePad UDF
    def prePadding: mutable.WrappedArray[java.lang.Double] => Array[Float] = x => {
      if (x.array.size < maxLength) x.array.map(_.toFloat).reverse.padTo(maxLength, 0f).reverse
      else x.array.map(_.toFloat).takeRight(maxLength)
    }

    val prePaddingUDF = udf(prePadding)

 // slide UDF
    def slide: mutable.WrappedArray[java.lang.Double] => Array[mutable.WrappedArray[java.lang.Double]] = x => {
      x.grouped(maxLength + 1).toArray
    }
    val slideUDF = udf(slide)

    val sessionDFSlided = sessionDF.withColumn("ATC_SEQ", explode(slideUDF(col("ATC_SEQ"))))

    import sqlContext.implicits._
    val sessionDFExpand = sessionDFSlided.rdd.flatMap( x => {
      val raws = x.getAs[mutable.WrappedArray[java.lang.Double]]("ATC_SEQ").array
      val pur = x.getAs[mutable.WrappedArray[java.lang.Double]]("PURCH_HIST").array
      val out = for ( raw <- raws.dropRight(1)) yield {
        val idx = raws.indexOf(raw) + 1
        val input = raws.take(idx)
        val output = raws.diff(input).take(1)
        (input, output, pur)
      }
      out
    }).toDF("ATC_SEQ", "label", "PURCH_HIST").na.drop()

    val rnnDF = sessionDFExpand
      .withColumn("ATC", prePaddingUDF(col("ATC_SEQ")))
      .withColumn("PUR", prePaddingUDF(col("PURCH_HIST")))
      .withColumn("label", col("label").getItem(0))
      .select("ATC", "PUR", "label")

    // dataFrame to rdd of sample
    val trainSample = rnnDF.rdd.map(r => {
      val label = Tensor[Float](T(r.getAs[Float]("label")))
      val mlpFeature = r.getAs[mutable.WrappedArray[java.lang.Float]]("PUR").array.map(_.toFloat)
      val rnnFeature = r.getAs[mutable.WrappedArray[java.lang.Float]]("ATC").array.map(_.toFloat)
      val mlpSample = Tensor(mlpFeature, Array(maxLength))
      val rnnSample = Tensor(rnnFeature, Array(maxLength))
      TensorSample[Float](Array(mlpSample, rnnSample), Array(label))
    })

    println("Sample feature print: \n" + trainSample.take(1).head.feature(0))
    println("Sample feature print: \n" + trainSample.take(1).head.feature(1))
    println("Sample label print: \n" + trainSample.take(1).head.label())

    trainSample
  }

}
