package com.sdy;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sdy.common.domain.Constant;
import com.sdy.func.AsyncHbaseDimBaseDicFunc;

import com.sdy.func.IntervalJoinOrderCommentAndOrderInfoFunc;
import com.sdy.utils.CommonGenerateTempLate;
import com.sdy.utils.DateTimeUtils;
import com.sdy.utils.KafkaUtils;
import com.sdy.utils.SensitiveWordsUtils;
import lombok.SneakyThrows;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.time.Time;


import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * @Package com.sdy.retailersv1.DbusDBCommentFactData2Kafka
 * @Author danyu-shi
 * @Date 2025/5/7 14:50
 * @description: Read MySQL CDC binlog data to kafka topics Task-03
 * TODO 该任务，修复了之前SQL的代码逻辑，在之前的逻辑中使用了FlinkSQL的方法进行了实现，把去重的问题，留给了下游的DWS，这种行为非常的yc
 * TODO Before FlinkSQL Left join and use hbase look up join func ,left join 产生的2条异常数据，会在下游做处理，一条为null，一条为未关联上的数据
 * TODO After FlinkAPI Async and google guava cache
 */

public class DbusDBCommentFactData2Kafka {

    private static final ArrayList<String> sensitiveWordsLists;

    static {
        sensitiveWordsLists = SensitiveWordsUtils.getSensitiveWordsLists();
    }
//
//    private static final String kafka_botstrap_servers = ConfigUtils.getString("kafka.bootstrap.servers");
//    private static final String kafka_cdc_db_topic = ConfigUtils.getString("kafka.cdc.db.topic");
//    private static final String kafka_db_fact_comment_topic = ConfigUtils.getString("kafka.db.fact.comment.topic");

    @SneakyThrows
    public static void main(String[] args) {

        System.setProperty("HADOOP_USER_NAME","root");

        // TODO -XX:ReservedCodeCacheSize=256m -XX:+UseCodeCacheFlushing -XX:CodeCacheMinimumFreeSpace=20%

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
//        EnvironmentSettingUtils.defaultParameter(env);
        env.setParallelism(10);

        // 评论表 取数
        SingleOutputStreamOperator<String> kafkaCdcDbSource = env.fromSource(
                KafkaUtils.buildKafkaSource(
                        Constant.KAFKA_BROKERS,
                        Constant.TOPICGl,
                        new Date().toString(),
                        OffsetsInitializer.earliest()
                ),
                WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofSeconds(3))
                        .withTimestampAssigner((event, timestamp) -> JSONObject.parseObject(event).getLong("ts_ms")),
                "kafka_cdc_db_source"
        ).uid("kafka_cdc_db_source").name("kafka_cdc_db_source");




        // 订单主表
        DataStream<JSONObject> filteredOrderInfoStream = kafkaCdcDbSource
                .map(JSON::parseObject)
                .filter(json -> json.getJSONObject("source").getString("table").equals("order_info"))
                .uid("kafka_cdc_db_order_source").name("kafka_cdc_db_order_source");
//        filteredOrderInfoStream.print();

//        // 评论表进行进行升维处理 和hbase的维度进行关联补充维度数据
        DataStream<JSONObject> filteredStream = kafkaCdcDbSource
                .map(JSON::parseObject)
                .filter(json -> json.getJSONObject("source").getString("table").equals("comment_info"))
                .keyBy(json -> json.getJSONObject("after").getString("appraise"));

//        filteredStream.print();
//
        DataStream<JSONObject> enrichedStream = AsyncDataStream
                .unorderedWait(
                        filteredStream,
                        new AsyncHbaseDimBaseDicFunc(),
                        60,
                        TimeUnit.SECONDS,
                        100
                ).uid("async_hbase_dim_base_dic_func")
                .name("async_hbase_dim_base_dic_func");

//        enrichedStream.print();



        SingleOutputStreamOperator<JSONObject> orderCommentMap = enrichedStream.map(new RichMapFunction<JSONObject, JSONObject>() {
                    @Override
                    public JSONObject map(JSONObject jsonObject){
                        JSONObject resJsonObj = new JSONObject();
                        Long tsMs = jsonObject.getLong("ts_ms");
                        JSONObject source = jsonObject.getJSONObject("source");
                        String dbName = source.getString("db");
                        String tableName = source.getString("table");
                        String serverId = source.getString("server_id");
                        if (jsonObject.containsKey("after")) {
                            JSONObject after = jsonObject.getJSONObject("after");
                            resJsonObj.put("ts_ms", tsMs);
                            resJsonObj.put("db", dbName);
                            resJsonObj.put("table", tableName);
                            resJsonObj.put("server_id", serverId);
                            resJsonObj.put("appraise", after.getString("appraise"));
                            resJsonObj.put("commentTxt", after.getString("comment_txt"));
                            resJsonObj.put("op", jsonObject.getString("op"));
                            resJsonObj.put("nick_name", jsonObject.getString("nick_name"));
                            resJsonObj.put("create_time", after.getLong("create_time"));
                            resJsonObj.put("user_id", after.getLong("user_id"));
                            resJsonObj.put("sku_id", after.getLong("sku_id"));
                            resJsonObj.put("id", after.getLong("id"));
                            resJsonObj.put("spu_id", after.getLong("spu_id"));
                            resJsonObj.put("order_id", after.getLong("order_id"));
                            resJsonObj.put("dic_name", after.getString("dic_name"));
                            return resJsonObj;
                        }
                        return null;
                    }
                })
                .uid("map-order_comment_data")
                .name("map-order_comment_data");

//           orderCommentMap.print();

        SingleOutputStreamOperator<JSONObject> orderInfoMapDs = filteredOrderInfoStream.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject inputJsonObj){
                String op = inputJsonObj.getString("op");
                long tm_ms = inputJsonObj.getLongValue("ts_ms");
                JSONObject dataObj;
                if (inputJsonObj.containsKey("after") && !inputJsonObj.getJSONObject("after").isEmpty()) {
                    dataObj = inputJsonObj.getJSONObject("after");
                } else {
                    dataObj = inputJsonObj.getJSONObject("before");
                }
                JSONObject resultObj = new JSONObject();
                resultObj.put("op", op);
                resultObj.put("tm_ms", tm_ms);
                resultObj.putAll(dataObj);
                return resultObj;
            }
        }).uid("map-order_info_data").name("map-order_info_data");

//        orderInfoMapDs.print();

        // orderCommentMap.order_id join orderInfoMapDs.id
        KeyedStream<JSONObject, String> keyedOrderCommentStream = orderCommentMap.keyBy(data -> data.getString("order_id"));
        KeyedStream<JSONObject, String> keyedOrderInfoStream = orderInfoMapDs.keyBy(data -> data.getString("id"));

        // {"info_original_total_amount":"56092.00","info_activity_reduce_amount":"1199.90","commentTxt":"评论内容：52198813817222113474133821791377912858419193882331","info_province_id":8,"info_payment_way":"3501","info_create_time":1746624020000,"info_refundable_time":1747228820000,"info_order_status":"1002","id":84,"spu_id":3,"table":"comment_info","info_tm_ms":1746596796189,"info_operate_time":1746624052000,"op":"c","create_time":1746624077000,"info_user_id":178,"info_op":"u","info_trade_body":"Apple iPhone 12 (A2404) 64GB 白色 支持移动联通电信5G 双卡双待手机等6件商品","sku_id":11,"server_id":"1","dic_name":"好评","info_consignee_tel":"13316189177","info_total_amount":"54892.10","info_out_trade_no":"692358523797933","appraise":"1201","user_id":178,"info_id":1010,"info_coupon_reduce_amount":"0.00","order_id":1010,"info_consignee":"彭永","ts_ms":1746596796318,"db":"realtime_v1"}
        SingleOutputStreamOperator<JSONObject> orderMsgAllDs = keyedOrderCommentStream.intervalJoin(keyedOrderInfoStream)
                .between(Time.minutes(-1), Time.minutes(1))
                .process(new IntervalJoinOrderCommentAndOrderInfoFunc())
                .uid("interval_join_order_comment_and_order_info_func").name("interval_join_order_comment_and_order_info_func");

//        orderMsgAllDs.print();
        // 通过AI 生成评论数据，`Deepseek 7B` 模型即可
//         {"info_original_total_amount":"1299.00","info_activity_reduce_amount":"0.00","commentTxt":"\n\n这款Redmi 10X虽然价格亲民，但续航能力一般且相机效果平平，在同类产品中竞争力不足。","info_province_id":32,"info_payment_way":"3501","info_create_time":1746566254000,"info_refundable_time":1747171054000,"info_order_status":"1004","id":75,"spu_id":2,"table":"comment_info","info_tm_ms":1746518021300,"info_operate_time":1746563573000,"op":"c","create_time":1746563573000,"info_user_id":149,"info_op":"u","info_trade_body":"Redmi 10X 4G Helio G85游戏芯 4800万超清四摄 5020mAh大电量 小孔全面屏 128GB大存储 8GB+128GB 明月灰 游戏智能手机 小米 红米等1件商品","sku_id":7,"server_id":"1","dic_name":"好评","info_consignee_tel":"13144335624","info_total_amount":"1299.00","info_out_trade_no":"199223184973112","appraise":"1201","user_id":149,"info_id":327,"info_coupon_reduce_amount":"0.00","order_id":327,"info_consignee":"范琳","ts_ms":1746518021294,"db":"realtime_v1"}
        SingleOutputStreamOperator<JSONObject> supplementDataMap = orderMsgAllDs.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject) {
                jsonObject.put("commentTxt", CommonGenerateTempLate.GenerateComment(jsonObject.getString("dic_name"), jsonObject.getString("info_trade_body")));
                return jsonObject;
            }
        }).uid("map-generate_comment").name("map-generate_comment");

//        supplementDataMap.print();
//
        SingleOutputStreamOperator<JSONObject> suppleMapDs = supplementDataMap.map(new RichMapFunction<JSONObject, JSONObject>() {
            private transient Random random;

            @Override
            public void open(Configuration parameters){
                random = new Random();
            }

            @Override
            public JSONObject map(JSONObject jsonObject){
                if (random.nextDouble() < 0.2) {
                    jsonObject.put("commentTxt", jsonObject.getString("commentTxt") + "," + SensitiveWordsUtils.getRandomElement(sensitiveWordsLists));
                    System.err.println("change commentTxt: " + jsonObject);
                }
                return jsonObject;
            }
        }).uid("map-sensitive-words").name("map-sensitive-words");

//        suppleMapDs.print();

        SingleOutputStreamOperator<JSONObject> suppleTimeFieldDs = suppleMapDs.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject){
                jsonObject.put("ds", DateTimeUtils.format(new Date(jsonObject.getLong("ts_ms")), "yyyyMMdd"));
                return jsonObject;
            }
        }).uid("add json ds").name("add json ds");

        suppleTimeFieldDs.print();

//        suppleTimeFieldDs.map(js -> js.toJSONString())
//                .sinkTo(
//                KafkaUtils.buildKafkaSink(kafka_botstrap_servers, kafka_db_fact_comment_topic)
//        ).uid("kafka_db_fact_comment_sink").name("kafka_db_fact_comment_sink");


        env.execute();
    }
}
