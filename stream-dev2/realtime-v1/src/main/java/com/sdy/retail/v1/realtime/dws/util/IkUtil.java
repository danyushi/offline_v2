package com.sdy.retail.v1.realtime.dws.util;

import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/**
 * @Package com.sdy.retail.v1.realtime.dws.util.IkUtil
 * @Author danyu-shi
 * @Date 2025/4/15 16:59
 * @description:
 */
public class IkUtil {
    public static Set<String> split(String s) {
        Set<String> result = new HashSet<>();
        // String => Reader

        Reader reader = new StringReader(s);
        // 智能分词
        // max_word
        IKSegmenter ikSegmenter = new IKSegmenter(reader, true);

        try {
            Lexeme next = ikSegmenter.next();
            while (next != null) {
                String word = next.getLexemeText();
                result.add(word);
                next = ikSegmenter.next();
            }

        }  catch (IOException e) {
            throw new RuntimeException(e);
        }


        return result;
    }

//    public static void main(String[] args) {
//        System.out.println("小米手机5G移动联通自营");
//    }
}


