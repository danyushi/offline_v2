package com.sdy;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.PropertyNamingStrategy;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.sdy.common.domain.Constant;
import com.sdy.common.utils.FlinkSinkUtil;
import com.sdy.common.utils.KafkaUtil;
import com.sdy.func.FilterBloomDeduplicatorFunc;
import com.sdy.func.MapCheckRedisSensitiveWordsFunc;
import com.sdy.st.userinfo;
import com.sdy.utils.ConfigUtils;
import com.sdy.utils.EnvironmentSettingUtils;
import com.sdy.utils.KafkaUtils;
import lombok.SneakyThrows;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import com.github.houbb.sensitive.word.core.SensitiveWordHelper;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @Package com.retailersv1.DbusBanBlackListUserInfo2Kafka
 * @Author danyu-shi
 * @Date 2025/5/8 8:46
 * @description: 黑名单封禁 Task 04
 * @Test
 * DataStreamSource<String> kafkaCdcDbSource = env.socketTextStream("127.0.0.1", 9999);
 */
public class DbusBanBlackListUserInfo2Kafka {

    @SneakyThrows
    public static void main(String[] args) {

        System.setProperty("HADOOP_USER_NAME","root");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        env.enableCheckpointing(1000 * 30);
        env.setRestartStrategy(RestartStrategies.failureRateRestart(5, Time.of(5, TimeUnit.MINUTES), Time.of(10, TimeUnit.SECONDS)));


        SingleOutputStreamOperator<String> kafkaCdcDbSource = env.fromSource(
                KafkaUtils.buildKafkaSource(
                        Constant.KAFKA_BROKERS,
                        Constant.TOPICinfo2,
                        new Date().toString(),
                        OffsetsInitializer.earliest()
                ),
                WatermarkStrategy.noWatermarks(),
                "kafka_cdc_db_source"
        ).uid("kafka_fact_comment_source").name("kafka_fact_comment_source");

//        kafkaCdcDbSource.print();
//         {"info_original_total_amount":"24038.00","info_activity_reduce_amount":"0.00","commentTxt":"\n\n小米电视4A屏幕显示效果一般，建议谨慎购买。","info_province_id":14,"info_payment_way":"3501","ds":"20250506","info_create_time":1746543508000,"info_refundable_time":1747148308000,"info_order_status":"1001","id":68,"spu_id":6,"table":"comment_info","info_tm_ms":1746518018698,"op":"c","create_time":1746543560000,"info_user_id":26,"info_op":"c","info_trade_body":"小米电视4A 70英寸 4K超高清 HDR 二级能效 2GB+16GB L70M5-4A 内置小爱 智能网络液晶平板教育电视等8件商品","sku_id":21,"server_id":"1","dic_name":"好评","info_consignee_tel":"13477763374","info_total_amount":"23968.00","info_out_trade_no":"443513674664624","appraise":"1201","user_id":26,"info_id":836,"info_coupon_reduce_amount":"70.00","order_id":836,"info_consignee":"穆素云","ts_ms":1746518019174,"db":"realtime_v1"}
        SingleOutputStreamOperator<JSONObject> mapJsonStr = kafkaCdcDbSource.map(JSON::parseObject).uid("to_json_string").name("to_json_string");


        SingleOutputStreamOperator<JSONObject> bloomFilterDs = mapJsonStr.keyBy(data -> data.getLong("order_id"))
                .filter(new FilterBloomDeduplicatorFunc(1000000, 0.01));
//        bloomFilterDs.print();


        // {"msg":"\n\nTCL 85Q6电视效果与价格不匹配！,西毒是共党","consignee":"臧宁欣","violation_grade":"P0","user_id":221,"violation_msg":"西毒是共党","is_violation":1,"ts_ms":1746596800252,"ds":"20250507"}
        SingleOutputStreamOperator<JSONObject> SensitiveWordsDs = bloomFilterDs.map(new MapCheckRedisSensitiveWordsFunc())
                .uid("MapCheckRedisSensitiveWord")
                .name("MapCheckRedisSensitiveWord");

//        SensitiveWordsDs.print();

        SingleOutputStreamOperator<JSONObject> secondCheckMap = SensitiveWordsDs.map(new RichMapFunction<JSONObject, JSONObject>() {
            @Override
            public JSONObject map(JSONObject jsonObject) {
                if (jsonObject.getIntValue("is_violation") == 0) {
                    String msg = jsonObject.getString("msg");
                    List<String> msgSen = SensitiveWordHelper.findAll(msg);
                    if (msgSen.size() > 0) {
                        jsonObject.put("violation_grade", "P1");
                        jsonObject.put("violation_msg", String.join(", ", msgSen));
                    }
                }
                return jsonObject;
            }
        }).uid("second sensitive word check").name("second sensitive word check");

//        secondCheckMap.print();

//        secondCheckMap.map(data -> data.toJSONString())
//                        .sinkTo(
//                                KafkaUtil.getKafkaSink("stream-DbusBanBlack-danyushi")
//                        );

        DataStreamSource<String> dbStrDS = KafkaUtil.getKafkaSource(env, "stream-DbusBanBlack-danyushi", "DbusBanBlack");

        dbStrDS.print();
        dbStrDS.sinkTo(FlinkSinkUtil.getDorisSink("DbusBanBlackListUserInfo"));


        env.execute();
    }
}
