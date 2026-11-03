package com.dekk.yanyu;

import android.content.Context;
import java.lang.reflect.Method;

/** 消息正文中的原生 AndroidView 转账卡片。 */
final class ConversationTransfers implements TransferCardView.Actions {
    private static ConversationTransfers instance;
    final HostMessageDispatch dispatch;
    private final Method androidView;
    private ConversationTransfers(ClassLoader loader)throws Exception{
        dispatch=new HostMessageDispatch(loader);
        androidView=dispatch.type("oy4").getMethod("a",dispatch.type("sv3"),dispatch.type("c76"),dispatch.type("sv3"),dispatch.type("sv3"),dispatch.type("ey3"),int.class);
    }
    static void install(ClassLoader loader){
        if(instance!=null)return;
        try{instance=new ConversationTransfers(loader);HotBridge.log("[DEKK][转账] 消息内卡片已安装");}
        catch(Exception e){HotBridge.log("[DEKK][转账] 安装失败: "+e);}
    }
    static HostMessageDispatch dispatcher(){return instance==null?null:instance.dispatch;}
    private VirtualTransferStore store(){return new VirtualTransferStore(HotApp.currentApplication());}
    static boolean render(Object[] args,Object composer,ReplySegments.Part part,boolean streaming)throws Exception{
        if(instance==null||!TransferStore.promptEnabled(HotApp.currentApplication()))return false;
        if(!part.complete&&VirtualTransferProtocol.incomplete(part.text))return true;
        String amount=VirtualTransferProtocol.amount(part.text);if(amount==null)return false;
        String session=(String)args[0];int message=(Integer)args[1];
        VirtualTransferProtocol.Id id=null;
        if(session!=null&&session.length()>0&&message>0)id=new VirtualTransferProtocol.Id(session,message,HotHelpers.getIntField(args[9],"c"),part.key,amount);
        instance.emit(composer,id,amount,streaming);return true;
    }
    private void emit(Object composer,final VirtualTransferProtocol.Id id,final String amount,final boolean streaming)throws Exception{
        Object factory=dispatch.function("sv3",new HostMessageDispatch.Body(){public Object run(Object[] a){return new TransferCardView((Context)a[0],store());}});
        Object release=dispatch.function("sv3",new HostMessageDispatch.Body(){public Object run(Object[] a){((TransferCardView)a[0]).release();return dispatch.unit;}});
        Object update=dispatch.function("sv3",new HostMessageDispatch.Body(){public Object run(Object[] a){((TransferCardView)a[0]).bind(id,amount,false,streaming,ConversationTransfers.this);return dispatch.unit;}});
        Object modifier=HotHelpers.getStaticObjectField(dispatch.type("z66"),"a");
        modifier=HotHelpers.callStaticMethod(dispatch.type("jt5"),"I",modifier,16f,8f,16f,8f);
        androidView.invoke(null,factory,modifier,release,update,composer,0);
    }
    public void decide(VirtualTransferProtocol.Id id,boolean received)throws Exception{
        if(!TransferStore.promptEnabled(HotApp.currentApplication()))throw new IllegalStateException("虚拟转账未启用");
        dispatch.ready(id.session);VirtualTransferStore store=store();
        if(store.get(id)!=null)return;
        retry(store.decide(id,received));
    }
    public void retry(final VirtualTransferStore.Record record)throws Exception{
        final VirtualTransferStore store=store();
        if(record.delivery==VirtualTransferStore.SENT)return;
        dispatch.ready(record.id.session);store.delivery(record,VirtualTransferStore.SENDING);
        try{dispatch.send(record.id.session,record.notification(),record.token,new HostMessageDispatch.Completion(){public void done(boolean delivered){store.delivery(record,delivered?VirtualTransferStore.SENT:VirtualTransferStore.FAILED);}});}
        catch(Exception e){store.delivery(record,VirtualTransferStore.FAILED);throw e;}
    }
}
