package com.stream;

import lombok.SneakyThrows;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;


/**
 * @Package com.sdy.stream.FlinkNcMessage
 * @Author danyu-shi
 * @Date 2025/5/7 14:50
 * @description: Test Nc Jar
 */

public class FlinkNcMessage {

    @SneakyThrows
    public static void main(String[] args) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();


        DataStreamSource<String> dataStreamSource = env.socketTextStream("cdh03", 14777);

        dataStreamSource.print();

        env.execute();
    }
}
