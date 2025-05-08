package com.sdy.st;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Package com.sdy.st.userinfo
 * @Author danyu-shi
 * @Date 2025/5/8 18:43
 * @description:
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class userinfo {
    private String msg;
    private String consignee;
    private String violation_grade;
    private String user_id;
    private String violation_msg;
    private String is_violation;
    private String ts_ms;
    private String ds;



}
