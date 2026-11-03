package com.dekk.yanyu;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;
import org.json.JSONObject;

/** ilke 决策与通知状态存储，与旧版记录隔离。 */
final class VirtualTransferStore {
    static final int WAITING=0, SENDING=1, SENT=2, FAILED=3;
    private static final Object LOCK=new Object();
    final SharedPreferences preferences;
    VirtualTransferStore(Context context){preferences=context.getSharedPreferences("dekk_virtual_transfers",0);}
    static final class Record {
        final VirtualTransferProtocol.Id id;
        final boolean received;
        final String token;
        final int delivery;
        Record(VirtualTransferProtocol.Id id,boolean received,String token,int delivery){
            this.id=id;this.received=received;this.token=token;this.delivery=delivery;
        }
        String notification(){return (received?"对方已收款":"对方已退回")+"\n虚拟转账金额：¥"+id.amount+"\n[ilke-transfer:"+token+"]";}
    }
    Record get(VirtualTransferProtocol.Id id){return read(id.key);}
    Record findNotification(String session,String text){
        String token=VirtualTransferProtocol.eventToken(text);
        if(session==null||token==null)return null;
        String key=preferences.getString("event:"+token,null);
        Record record=key==null?null:read(key);
        return record!=null&&record.id.session.equals(session)&&record.notification().equals(text)?record:null;
    }
    Record decide(VirtualTransferProtocol.Id id,boolean received){synchronized(LOCK){
        Record old=get(id);if(old!=null)return old;
        Record record=new Record(id,received,UUID.randomUUID().toString(),WAITING);write(record);return record;
    }}
    Record delivery(Record record,int value){synchronized(LOCK){
        if(value<0||value>FAILED)throw new IllegalArgumentException("Delivery state");
        Record old=get(record.id);
        if(old==null||!old.token.equals(record.token))throw new IllegalStateException("Unknown transfer decision");
        if(old.delivery==SENT)return old;
        Record next=new Record(old.id,old.received,old.token,value);write(next);return next;
    }}
    private Record read(String key){
        String value=preferences.getString("transfer:"+key,null);if(value==null)return null;
        try{
            JSONObject json=new JSONObject(value);
            VirtualTransferProtocol.Id id=new VirtualTransferProtocol.Id(json.getString("session"),json.getInt("message"),json.getInt("fragment"),json.getInt("segment"),json.getString("amount"));
            if(!key.equals(id.key))throw new IllegalStateException("Transfer key mismatch");
            String token=json.getString("token");UUID.fromString(token);
            int delivery=json.getInt("delivery");if(delivery<0||delivery>FAILED)throw new IllegalStateException("Delivery state");
            return new Record(id,json.getBoolean("received"),token,delivery);
        }catch(Exception e){throw new IllegalStateException("转账记录读取失败",e);}
    }
    private void write(Record record){try{
        JSONObject json=new JSONObject();
        json.put("session",record.id.session);json.put("message",record.id.message);
        json.put("fragment",record.id.fragment);json.put("segment",record.id.segment);
        json.put("amount",record.id.amount);json.put("received",record.received);
        json.put("token",record.token);json.put("delivery",record.delivery);
        if(!preferences.edit().putString("transfer:"+record.id.key,json.toString()).putString("event:"+record.token,record.id.key).commit())
            throw new IllegalStateException("转账记录保存失败");
    }catch(org.json.JSONException e){throw new IllegalStateException(e);}}
}
