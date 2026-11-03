package com.dekk.yanyu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析与同步：把网易云返回的 lrc.lyric 拆成"时间 → 一行"，
 * 按播放位置取当前行/下一行。纯逻辑，方便自检。
 */
final class MusicLyric {
    static final class Line {
        final long at;
        final String text;
        Line(long at,String text){this.at=at;this.text=text;}
    }

    /** [mm:ss.xx] / [mm:ss:xx] / [mm:ss]，一行可以有多个时间标签。 */
    private static final Pattern STAMP=Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    /** [ti:] [ar:] [al:] [by:] [offset:] 这类元信息不算歌词。 */
    private static final Pattern META=Pattern.compile("^\\[(ti|ar|al|by|offset|re|ve|length):.*",Pattern.CASE_INSENSITIVE);

    private final List<Line> lines=new ArrayList<Line>();

    private MusicLyric() { }

    static MusicLyric parse(String lrc){
        MusicLyric result=new MusicLyric();
        if(lrc==null||lrc.length()==0)return result;
        for(String raw:lrc.split("\\r?\\n")){
            String row=raw.trim();
            if(row.length()==0||META.matcher(row).matches())continue;
            Matcher matcher=STAMP.matcher(row);
            List<Long> stamps=new ArrayList<Long>();
            int end=0;
            while(matcher.find()){
                stamps.add(toMillis(matcher.group(1),matcher.group(2),matcher.group(3)));
                end=matcher.end();
            }
            if(stamps.isEmpty())continue;
            // 行内可能有翻译/多语言，只取第一段文本，避免两行叠在一起。
            String text=row.substring(end).trim();
            if(text.length()==0)continue;
            for(Long at:stamps)result.lines.add(new Line(at.longValue(),text));
        }
        Collections.sort(result.lines,new Comparator<Line>(){
            public int compare(Line left,Line right){
                return left.at<right.at?-1:(left.at>right.at?1:0);
            }
        });
        return result;
    }

    /** 最近一次解析结果缓存：每秒刷新时不必重复解析同一份 lrc。 */
    private static String cachedFrom;
    private static MusicLyric cached;

    static MusicLyric of(String lrc){
        if(lrc==null)lrc="";
        if(cached!=null&&lrc.equals(cachedFrom))return cached;
        MusicLyric parsed=parse(lrc);
        cachedFrom=lrc;
        cached=parsed;
        return parsed;
    }

    boolean empty(){return lines.isEmpty();}

    int size(){return lines.size();}

    /** 当前位置该显示的那一行；还没到第一行时返回空串。 */
    String lineAt(long positionMs){
        String found="";
        for(int i=0;i<lines.size();i++){
            Line line=lines.get(i);
            if(line.at<=positionMs)found=line.text;
            else break;
        }
        return found;
    }

    /** 距离当前位置最近、还没到的那一行（做"下一句"提示用）。 */
    String nextLine(long positionMs){
        for(int i=0;i<lines.size();i++){
            if(lines.get(i).at>positionMs)return lines.get(i).text;
        }
        return "";
    }

    /** 当前位置那一句的上一句；还没唱到第一句或没有上一句时返回空串。 */
    String previousLine(long positionMs){
        if(lineAt(positionMs).length()==0)return "";
        int index=indexAt(positionMs);
        return index>0?lines.get(index-1).text:"";
    }

    /** 当前行下标；还没开始返回 0。 */
    int indexAt(long positionMs){
        int index=0;
        for(int i=0;i<lines.size();i++){
            if(lines.get(i).at<=positionMs)index=i;
            else break;
        }
        return index;
    }

    /** 取第 index 行（越界返回空串），给页面画多行歌词用。 */
    String line(int index){
        if(index<0||index>=lines.size())return "";
        return lines.get(index).text;
    }

    Line lineObject(int index){
        if(index<0||index>=lines.size())return null;
        return lines.get(index);
    }

    private static long toMillis(String minute,String second,String fraction){
        long minutes=Long.parseLong(minute);
        long seconds=Long.parseLong(second);
        long millis=0L;
        if(fraction!=null&&fraction.length()>0){
            long value=Long.parseLong(fraction);
            if(fraction.length()==1)millis=value*100L;
            else if(fraction.length()==2)millis=value*10L;
            else millis=value;
        }
        return minutes*60000L+seconds*1000L+millis;
    }
}