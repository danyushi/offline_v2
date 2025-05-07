package com.stream.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Package com.sdy.stream.domain.MySQLMessageInfo
 * @Author danyu-shi
 * @Date 2025/5/7 14:50
 * @description: binlog
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class MySQLMessageInfo {
    private String id;
    private String op;
    private String db_name;
    private String log_before;
    private String log_after;
    private String t_name;
    private String ts;
}
