package com.gh.yun

/**
  * Created by GH-GAN on 2016/6/3.
  * 保存入es时，将app和容器只分为两个队列
  */

import com.gh.utils.{ConfigUtil, JsonUtil}
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka010.ConsumerStrategies._
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies._
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.codehaus.jackson.JsonNode
import org.elasticsearch.spark.streaming._

object SaveToESLog2 {
  def main(args: Array[String]) {
    Logger.getRootLogger.setLevel(Level.WARN)
    val conf = new SparkConf().setMaster(ConfigUtil.sparkmaster).setAppName("saveToesLog");
    conf.set("es.index.auto.create", "true")
    conf.set("es.nodes", ConfigUtil.esnodes)

    val ssc = new StreamingContext(conf,Seconds(ConfigUtil.logStreamSaveTime))
    ssc.checkpoint(ConfigUtil.saveLogcheckpoint)

    var brokers = ConfigUtil.brokers
    val _topics = "log-file".split(",").toSet
    val group = "saveToesLog"

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> group,
      "auto.offset.reset" -> "latest",
      "heartbeat.interval.ms" -> "6000",
      "session.timeout.ms" -> "20000",
      "max.partition.fetch.bytes" -> "10485760",   //1048576000
      "max.poll.records" -> "10000",                 // message.max.bytes
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    val stream = KafkaUtils.createDirectStream[String, String](
      ssc,
      PreferConsistent,
      Subscribe[String, String](_topics, kafkaParams)
    )

    val datas = stream.map(line => {
      var node : JsonNode = JsonUtil.getJsonNode(line.value())
      (node,line.value())
    }).filter(x => x._1 != null && x._1.get("type") != null)
      //environment_id/container_uuid not null/""
      .filter(x => {
          var environment_id = ""
          var container_uuid = ""
          try{
              val data = x._1.get("data")
              environment_id = data.get("environment_id").asText()
              container_uuid = data.get("container_uuid").asText()
          }catch { case ex : Exception => ex.printStackTrace() }

          !"".equals(environment_id) && !"".equals(container_uuid)
       })
      .map(node => (node._1.get("type").asText(),node._2))

    // log
    ParseLogContainerSaveToEs(datas)  // log_container
    ParseLogFileSaveToEs(datas)      // log_app

    /*datas.foreachRDD(x => {
      x.foreach(y => println(y))
    })*/

    ssc.start()
    ssc.awaitTermination()

  }

  def ParseLogContainerSaveToEs(datas : DStream[(String,String)]): Unit ={
    val container = datas.filter(_._1.equals("container"))
    container.map(_._2).saveJsonToEs("yun-{data.environment_id}-{data.container_uuid}-containerlogfile-container-{data.log_info.log_time:YYYY-MM-dd}/container-log",Map("es.batch.size.bytes"->"1048576","es.batch.size.entries"->"1000","es.batch.write.refresh"->"false"))
  }

  def ParseLogFileSaveToEs(datas : DStream[(String,String)]): Unit ={
    val logfile = datas.filter(x => x._1.equals("nginx") || x._1.equals("mysql") || x._1.equals("redis"))
    logfile.map(_._2).saveJsonToEs("yun-{data.environment_id}-{data.container_uuid}-applogfile-{type}-{data.log_info.log_time:YYYY-MM-dd}/{type}-log",Map("es.batch.size.bytes"->"1048576","es.batch.size.entries"->"1000","es.batch.write.refresh"->"false"))
  }

}
