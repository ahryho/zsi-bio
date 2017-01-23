package com.zsibio

/**
  * Created by anastasiia on 10/6/16.
  */
import hex.FrameSplitter
import hex.deeplearning.DeepLearning
import hex.deeplearning.DeepLearningModel.DeepLearningParameters
import hex.deeplearning.DeepLearningModel.DeepLearningParameters.Activation
import org.apache.spark.{SparkConf, SparkContext}
//import org.apache.spark.SparkContext._
import org.apache.spark.h2o.H2OContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.formats.avro.{Genotype, GenotypeAllele}
import water.Key
import water.fvec.Frame

import scala.collection.JavaConverters._
import scala.collection.immutable.Range.inclusive
import scala.io.Source

object PopStrat {

  def main(args: Array[String]): Unit = {
    //val genotypeFile = args(0)
    //val panelFile = args(1)

    val genotypeFile = "/home/anastasiia/1000genomes/ALL.chrMT.phase3_callmom-v0_4.20130502.genotypes.vcf.adam"
    val panelFile = "/home/anastasiia/1000genomes/ALL.panel"

    print (genotypeFile)
    // val master = if (args.length > 2) Some(args(2)) else None
    val conf = new SparkConf().setAppName("PopStrat").setMaster("local")
      /*.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", "org.bdgenomics.adam.serialization.ADAMKryoRegistrator")
      .set("spark.executor.memory", "2g")*/
      /*.setJars(Seq("/lib/utils-metrics_2.10-0.2.3.jar"))*/
    // master.foreach(conf.setMaster)
    val sc = new SparkContext(conf)

    // Create a set of the populations that we want to predict
    // Then create a map of sample ID -> population so that we can filter out the samples we're not interested in
    val populations = Set("GBR", "ASW", "CHB", "GWD", "YRI", "IBS", "TSI")
    def extract(file: String, filter: (String, String) => Boolean): Map[String,String] = {
      Source.fromFile(file).getLines().map(line => {
        val tokens = line.split("\t").toList
        tokens(0) -> tokens(1)
      }).toMap.filter(tuple => filter(tuple._1, tuple._2))
    }
    val panel: Map[String,String] = extract(panelFile, (sampleID: String, pop: String) => populations.contains(pop))

    // Load the ADAM genotypes from the parquet file(s)
    // Next, filter the genotypes so that we're left with only those in the populations we're interested in
    val allGenotypes: RDD[Genotype] = sc.loadGenotypes(genotypeFile)
    val genotypes: RDD[Genotype] = allGenotypes.filter(genotype => {panel.contains(genotype.getSampleId)})

        // Convert the Genotype objects to our own SampleVariant objects to try and conserve memory
    case class SampleVariant(sampleId: String, variantId: Int, alternateCount: Int)
    def variantId(genotype: Genotype): String = {
      val name = genotype.getVariant.getContig.getContigName
      val start = genotype.getVariant.getStart
      val end = genotype.getVariant.getEnd
      s"$name:$start:$end"
    }
    def alternateCount(genotype: Genotype): Int = {
      genotype.getAlleles.asScala.count(_ != GenotypeAllele.Ref)
    }

    genotypes.foreach(g => g.getAlleles)
    def toVariant(genotype: Genotype): SampleVariant = {
      // Intern sample IDs as they will be repeated a lot
      new SampleVariant(genotype.getSampleId.intern(), variantId(genotype).hashCode(), alternateCount(genotype))
    }
    val variantsRDD: RDD[SampleVariant] = genotypes.map(toVariant)

    // Group the variants by sample ID so we can process the variants sample-by-sample
    // Then get the total number of samples. This will be used to find variants that are missing for some samples.
    // Group the variants by variant ID and filter out those variants that are missing from some samples
    val variantsBySampleId: RDD[(String, Iterable[SampleVariant])] = variantsRDD.groupBy(_.sampleId).cache()
   // val sampleCount: Long = variantsBySampleId.count()
    val sampleCount: Long = 255
    println("Found " + sampleCount + " samples")
   val variantsByVariantId: RDD[(Int, Iterable[SampleVariant])] = variantsRDD.groupBy(_.variantId).filter {
      case (_, sampleVariants) => sampleVariants.size == sampleCount
    }

    // Make a map of variant ID -> count of samples with an alternate count of greater than zero
    // then filter out those variants that are not in our desired frequency range. The objective here is simply to
    // reduce the number of dimensions in the data set to make it easier to train the model.
    // The specified range is fairly arbitrary and was chosen based on the fact that it includes a reasonable
    // number of variants, but not too many.
   val variantFrequencies: collection.Map[Int, Int] = variantsByVariantId.map {
      case (variantId, sampleVariants) => (variantId, sampleVariants.count(_.alternateCount > 0))
    }.collectAsMap()
    val permittedRange = inclusive(11, 11)
    val filteredVariantsBySampleId: RDD[(String, Iterable[SampleVariant])] = variantsBySampleId.map {
      case (sampleId, sampleVariants) =>
        val filteredSampleVariants = sampleVariants.filter(variant => permittedRange.contains(
          variantFrequencies.getOrElse(variant.variantId, -1)))
        (sampleId, filteredSampleVariants)
    }

    // Sort the variants for each sample ID. Each sample should now have the same number of sorted variants.
    // All items in the RDD should now have the same variants in the same order so we can just use the first
    // one to construct our header
    // Next construct the rows of our SchemaRDD from the variants
    val sortedVariantsBySampleId: RDD[(String, Array[SampleVariant])] = filteredVariantsBySampleId.map {
      case (sampleId, variants) =>
        (sampleId, variants.toArray.sortBy(_.variantId))
    }
    val header = StructType(Array(StructField("Region", StringType)) ++
      sortedVariantsBySampleId.first()._2.map(variant => {StructField(variant.variantId.toString, IntegerType)}))
    val rowRDD: RDD[Row] = sortedVariantsBySampleId.map {
      case (sampleId, sortedVariants) =>
        val region: Array[String] = Array(panel.getOrElse(sampleId, "Unknown"))
        val alternateCounts: Array[Int] = sortedVariants.map(_.alternateCount)
        Row.fromSeq(region ++ alternateCounts)
    }

    // Create the SchemaRDD from the header and rows and convert the SchemaRDD into a H2O dataframe
    val sqlContext = new org.apache.spark.sql.SQLContext(sc)
    val schemaRDD = sqlContext.applySchema(rowRDD, header)
    // val h2oContext = new H2OContext(sc).start()
    val h2oContext = H2OContext.getOrCreate(sc)
    import h2oContext._
    val dataFrame = h2oContext.asH2OFrame(schemaRDD)
    dataFrame.replace(dataFrame.find("Region"), dataFrame.vec("Region").toCategoricalVec()).remove()
    dataFrame.update()
    // Split the dataframe into 50% training, 30% test, and 20% validation data
    val frameSplitter = new FrameSplitter(dataFrame, Array(.5, .3), Array("training", "test", "validation").map(Key.make[Frame](_)), null)
    water.H2O.submitTask(frameSplitter)
    val splits = frameSplitter.getResult
    val training = splits(0)
    val validation = splits(2)

    print("\nTraining set:\n")
    print(training._key.get())
    print("\nValue class of the set:\n")
    print(validation._key.valueClass())

    // Set the parameters for our deep learning model.
    val deepLearningParameters = new DeepLearningParameters()
    deepLearningParameters._train = training._key.clone()
    deepLearningParameters._valid = validation._key.clone()
    deepLearningParameters._response_column = "Region"
    deepLearningParameters._epochs = 10
    deepLearningParameters._activation = Activation.RectifierWithDropout
    deepLearningParameters._hidden = Array[Int](100,100)

    // Train the deep learning model
    val deepLearning = new DeepLearning(deepLearningParameters)
    val deepLearningModel = deepLearning.trainModel.get

    // Score the model against the entire dataset (training, test, and validation data)
    // This causes the confusion matrix to be printed
    deepLearningModel.score(dataFrame)//('predict)*/
    print("\n---------- Ok ----------\n")
  }

}
