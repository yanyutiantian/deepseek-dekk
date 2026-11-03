package com.dekk.yanyu;

import java.util.ArrayList;
import java.util.List;

/** 分段只控制普通文本；转账独立行始终作为独立卡片，不能被五段上限合并。 */
final class MessageRenderParts {
    static List<ReplySegments.Part> parse(String text, boolean streaming, boolean split, boolean transfers) {
        List<ReplySegments.Part> base=ReplySegments.parse(text,streaming).parts;
        List<ReplySegments.Part> out=new ArrayList<ReplySegments.Part>();
        StringBuilder pending=new StringBuilder();
        boolean complete=true;
        int textCount=0;
        for(ReplySegments.Part part:base){
            StringBuilder chunk=new StringBuilder();
            char fence=0; int fenceSize=0;
            String[] lines=part.text.split("\n",-1);
            for(int i=0;i<lines.length;i++){
                String line=lines[i],trim=line.trim();
                int run=0;
                char first=trim.length()==0?0:trim.charAt(0);
                if(first=='`'||first=='~')while(run<trim.length()&&trim.charAt(run)==first)run++;
                boolean boundary=false;
                if(run>=3){
                    if(fence==0){fence=first;fenceSize=run;boundary=true;}
                    else if(fence==first&&run>=fenceSize&&trim.substring(run).trim().length()==0){fence=0;boundary=true;}
                }
                boolean card=transfers&&!boundary&&fence==0&&VirtualTransferProtocol.amount(line)!=null;
                boolean partial=transfers&&!part.complete&&i==lines.length-1&&!boundary&&fence==0&&VirtualTransferProtocol.incomplete(line);
                if(card||partial){
                    append(pending,chunk.toString());chunk.setLength(0);
                    flush(out,pending,complete&&part.complete);
                    out.add(new ReplySegments.Part(out.size(),line,part.complete));
                    complete=true;
                }else{if(chunk.length()>0)chunk.append('\n');chunk.append(line);}
            }
            append(pending,chunk.toString());complete=complete&&part.complete;
            if(split&&textCount<4&&pending.length()>0){flush(out,pending,complete);textCount++;complete=true;}
        }
        flush(out,pending,complete);
        return out;
    }
    private static void append(StringBuilder target,String text){
        if(text.trim().length()==0)return;
        if(target.length()>0)target.append('\n');target.append(text);
    }
    private static void flush(List<ReplySegments.Part> out,StringBuilder text,boolean complete){
        if(text.toString().trim().length()>0)out.add(new ReplySegments.Part(out.size(),text.toString(),complete));
        text.setLength(0);
    }
    private MessageRenderParts(){}
}