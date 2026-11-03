package com.dekk.yanyu;
import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.lang.ref.WeakReference;

/** Headless platform Fragment receives the document picker result without broad Activity hooks. */
public final class PersonaTxtImport extends Fragment {
    private WeakReference<EditText> target;
    private WeakReference<CenterPage> page;
    public PersonaTxtImport(){}
    public static void pick(CenterPage p,EditText field){
        Activity a=p.activity;
        if(a.getFragmentManager().findFragmentByTag("dekk_txt_import")!=null){p.message("文件选择器正在运行");return;}
        PersonaTxtImport f=new PersonaTxtImport();f.target=new WeakReference<EditText>(field);f.page=new WeakReference<CenterPage>(p);
        a.getFragmentManager().beginTransaction().add(f,"dekk_txt_import").commit();
    }
    public void onCreate(Bundle saved){super.onCreate(saved);
        if(saved!=null || target==null){finish();return;}
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("text/plain");i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivityForResult(i,901);}catch(Exception e){CenterPage p=page.get();if(p!=null)p.message("无法打开文件选择器");finish();}
    }
    public void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=901)return;
        final Activity a=getActivity();final CenterPage p=page==null?null:page.get();final EditText field=target==null?null:target.get();
        if(result!=Activity.RESULT_OK || data==null || data.getData()==null || a==null || p==null || field==null){finish();return;}
        final Uri uri=data.getData();
        if(!"content".equals(uri.getScheme())){p.message("请选择系统文档提供器中的TXT文件");finish();return;}
        new Thread(new Runnable(){public void run(){String content=null,error=null;
            try{InputStream in=a.getContentResolver().openInputStream(uri);if(in==null)throw new IOException();ByteArrayOutputStream out=new ByteArrayOutputStream();
                try{byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>100000)throw new IOException("too large");out.write(buf,0,n);}}finally{in.close();}
                byte[] bytes=out.toByteArray();CharsetDecoder decoder=Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                content=decoder.decode(ByteBuffer.wrap(bytes)).toString();if(content.startsWith("\uFEFF"))content=content.substring(1);
                if(content.length()>20000 || content.trim().length()==0 || content.indexOf('\u0000')>=0)throw new IOException();
            }catch(Exception e){error="导入失败：请选择UTF-8编码、非空且不超过20000字的TXT";}
            final String text=content,msg=error;a.runOnUiThread(new Runnable(){public void run(){if(p.active()){if(msg!=null)p.message(msg);else field.setText(text);}finish();}});
        }},"DEKK-TXT").start();
    }
    private void finish(){if(isAdded())getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();}
}