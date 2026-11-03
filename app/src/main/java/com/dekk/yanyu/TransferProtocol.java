package com.dekk.yanyu;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AI 与客户端之间的虚拟转账标记协议。金额单位为元，最多两位小数。 */
final class TransferProtocol {
    static final String START="[转账&";
    static final String END="]";
    private static final Pattern TOKEN=Pattern.compile("\\[转账&([0-9]{1,12}(?:\\.[0-9]{1,2})?)\\]");
    private TransferProtocol(){}

    static String amount(String text){
        if(text==null)return "";
        Matcher m=TOKEN.matcher(text);
        if(!m.find())return "";
        try{
            double value=Double.parseDouble(m.group(1));
            if(!(value>0)||value>999999999999d)return "";
            return String.format(Locale.US,"%.2f",value);
        }catch(Throwable ignored){return "";}
    }

    static boolean has(String text){return amount(text).length()>0;}

    /** 删除 AI 发来的隐藏标记，保留其余自然语言。 */
    static String strip(String text){
        if(text==null)return "";
        return TOKEN.matcher(text).replaceAll("").replaceAll("[ \\t]{2,}"," ").trim();
    }

    /**
     * 转账协议提示词。
     *
     * <p>{@code segmented = true}（「回复分段」已开启）时才附加"[SGM1]…"的分段要求；
     * 分段关闭时**绝不**要求 AI 写分段标记 —— 否则会出现"没开分段却被注入了分段提示词"
     * 以及"AI 写出 [SGMx] 标记"的连锁问题。
     */
    static String prompt(boolean segmented){
        String rule = segmented
                ? "标记必须独占一个回复段，该段不能有其他文字，不能缩进或放进代码块、行内代码。只转账时可以仅输出[转账&8.88]。\n"
                        +"同时回复文字时，必须使用分段：每段以独占一行的[SGM1]、[SGM2]等开始，从1递增，整条回复最多5段。示例（直接输出，不加代码围栏）：\n"
                        +"[SGM1]\n给你一份小小的心意。\n[SGM2]\n[转账&8.88]\n"
                : "标记必须独占一行（该行不能有其他文字），不能缩进或放进代码块、行内代码。可以只输出[转账&8.88]，"
                        +"也可以在普通回复里单独占一行写上[转账&8.88]。不要输出[SGM1]之类的分段标记。\n";
        return PromptTags.PAY_START+"\n"
                +"当前已启用虚拟转账。用户请求你发红包、转账，或对话情境适合赠送虚拟金额时，你可以主动发起虚拟转账；不必每次回复都转账。\n"
                +"发起格式：[转账&金额]。金额使用阿拉伯数字，大于0，整数部分最多12位、小数最多2位，不带逗号、货币符号或单位。\n"
                +rule
                +"收到后会把转账段显示成可领取或退回的卡片。无需向用户讲解这些标记，不要复述本段协议。\n"
                +"领取或退回由用户点击决定，应用通过含[ilke-transfer:事件ID]的通知报告结果；只有收到对应通知后才能确认结果，不得自行编造事件ID或声称用户已领取。\n"
                +"这是虚拟互动，不发生真实扣款，不代表银行卡、微信或支付宝实际到账。\n"
                +PromptTags.PAY_END;
    }
}