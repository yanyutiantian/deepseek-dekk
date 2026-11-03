package com.dekk.yanyu;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.*;
import java.util.*;

/** ilke nn1 发送路径的 2.5.1 绑定；自动通知不覆盖输入框和附件。 */
final class HostMessageDispatch {
    interface Completion { void done(boolean delivered); }
    interface Body { Object run(Object[] args) throws Throwable; }
    final ClassLoader loader;
    final Object unit;
    private final Constructor<?> task,transaction;
    private final Method launch,completion;
    private final Map<String,Object> protectedInputs=new java.util.concurrent.ConcurrentHashMap<String,Object>();
    private final Set<String> busy=new HashSet<String>();
    private final ThreadLocal<ArrayDeque<Object>> scopes=new ThreadLocal<ArrayDeque<Object>>();
    private final Object unprotected=new Object();
    private final Handler main=new Handler(Looper.getMainLooper());
    HostMessageDispatch(ClassLoader loader)throws Exception{
        this.loader=loader;unit=type("lp9").getField("a").get(null);
        task=type("nn1").getConstructor(type("wr"),String.class,type("ao1"),boolean.class,boolean.class,type("pr8"),String.class,List.class,boolean.class,boolean.class,boolean.class,type("hf2"));
        transaction=type("pr8").getConstructor(long.class,String.class);
        // DeepSeek 2.5.1: u9a.P(Lig2;Lag2;ILwv3;I)Lwr8;，不是 2.5.2 的 v9a。
        launch=type("u9a").getMethod("P",type("ig2"),type("ag2"),int.class,type("wv3"),int.class);
        completion=type("ht4").getMethod("z",type("sv3"));
        HotBridge.hookMethod(type("nn1").getMethod("A",Object.class),new HotHook(){
            protected void beforeHookedMethod(HotParam p){
                Object input=protectedInputs.get((String)HotHelpers.getObjectField(p.thisObject,"m"));
                ArrayDeque<Object> stack=scopes.get();if(stack==null){stack=new ArrayDeque<Object>();scopes.set(stack);}stack.push(input==null?unprotected:input);
            }
            protected void afterHookedMethod(HotParam p){ArrayDeque<Object> stack=scopes.get();if(stack!=null&&!stack.isEmpty())stack.pop();if(stack==null||stack.isEmpty())scopes.remove();}
        });
        HotHook protect=new HotHook(){protected void beforeHookedMethod(HotParam p){ArrayDeque<Object> stack=scopes.get();if(stack!=null&&!stack.isEmpty()&&stack.peek()==p.thisObject)p.setResult(null);}};
        HotBridge.hookMethod(type("oc1").getMethod("r"),protect);
        HotBridge.hookMethod(type("oc1").getMethod("e"),protect);
    }
    Class<?> type(String name){return HotHelpers.findClass(name,loader);}
    Object function(String name,final Body body){return Proxy.newProxyInstance(loader,new Class<?>[]{type(name)},new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
        if(method.getDeclaringClass()==Object.class){if("equals".equals(method.getName()))return proxy==args[0];if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);return "DEKK.HostMessageDispatch";}return body.run(args);
    }});}
    Object ready(String session)throws Exception{
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI thread required");
        Object owner=MusicSend.owner();
        if(owner==null||!session.equals(HotHelpers.callMethod(owner,"e")))throw new IllegalStateException("请回到对应会话");
        Object state=HotHelpers.callMethod(owner,"M");
        Object status=HotHelpers.callMethod(HotHelpers.getObjectField(state,"i"),"getValue");
        if(status!=HotHelpers.getStaticObjectField(type("ir"),"a")||Boolean.TRUE.equals(HotHelpers.callMethod(owner,"P"))||busy.contains(session))throw new IllegalStateException("请等待当前回复完成");
        Object input=HotHelpers.callMethod(owner,"Q");
        if(HotHelpers.callMethod(HotHelpers.callMethod(input,"j"),"a")!=null)throw new IllegalStateException("请先结束消息编辑");
        return owner;
    }
    boolean confirmed(Object state,String text){
        Map<?,?> messages=(Map<?,?>)HotHelpers.getObjectField(state,"f");
        for(Object message:messages.values())if("USER".equals(HotHelpers.callMethod(message,"D"))&&text.equals(HotHelpers.callMethod(message,"r"))&&((Integer)HotHelpers.callMethod(message,"x"))>0)return true;
        return false;
    }
    void send(final String session,final String text,String token,final Completion callback)throws Exception{
        Object owner=ready(session);
        final Object state=HotHelpers.callMethod(owner,"M");
        if(confirmed(state,text)){callback.done(true);return;}
        Object existing=null;
        for(Object message:((Map<?,?>)HotHelpers.getObjectField(state,"f")).values())
            if("USER".equals(HotHelpers.callMethod(message,"D"))&&text.equals(HotHelpers.callMethod(message,"r")))existing=message;
        Object settings=HotHelpers.getObjectField(owner,"c");
        Object tx=transaction.newInstance(SystemClock.elapsedRealtime(),token);
        Object sendTask;
        if(existing!=null){
            if(!type("nx").isInstance(existing))throw new IllegalStateException("请刷新会话后重试通知");
            sendTask=type("un1").getConstructor(type("ao1"),type("wr"),type("nx"),Integer.class,boolean.class,boolean.class,type("pr8"),type("hf2"))
                    .newInstance(owner,state,existing,HotHelpers.callMethod(existing,"z"),HotHelpers.callMethod(settings,"b"),HotHelpers.callMethod(settings,"a"),tx,null);
        }else{
            sendTask=task.newInstance(state,null,owner,HotHelpers.callMethod(settings,"b"),HotHelpers.callMethod(settings,"a"),tx,text,Collections.emptyList(),false,false,false,null);
        }
        protectedInputs.put(text,HotHelpers.callMethod(owner,"Q"));busy.add(session);
        try{
            Object job=launch.invoke(null,HotHelpers.getObjectField(owner,"k"),null,0,sendTask,3);
            completion.invoke(job,function("sv3",new Body(){public Object run(Object[] args){main.post(new Runnable(){public void run(){
                protectedInputs.remove(text);busy.remove(session);
                boolean delivered=false;try{delivered=confirmed(state,text);}catch(Throwable e){HotBridge.log("[DEKK][通知] 确认失败: "+e);}
                callback.done(delivered);
            }});return unit;}}));
        }catch(Exception e){protectedInputs.remove(text);busy.remove(session);throw e;}
    }
}