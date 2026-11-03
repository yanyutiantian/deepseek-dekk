package com.dekk.yanyu;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;

/** Downloaded snapshots are independent of later server edits or deletion. */
public final class PersonaLocalStore {
    private PersonaLocalStore() { }
    public static synchronized void save(Context c, JSONObject persona) throws Exception {
        long id = persona.getLong("id"); if(id <= 0) throw new Exception("invalid id");
        File dir = new File(c.getFilesDir(), "dekk_personas");
        if(!dir.isDirectory() && !dir.mkdirs()) throw new Exception("storage unavailable");
        AtomicFile file = new AtomicFile(new File(dir,id + ".json"));
        FileOutputStream out = null;
        try { out=file.startWrite();out.write(persona.toString().getBytes("UTF-8"));file.finishWrite(out); }
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    public static synchronized boolean delete(Context c,long id){
        if(id<=0)return false;
        File file=new File(new File(c.getFilesDir(),"dekk_personas"),id+".json");
        new AtomicFile(file).delete();
        return !file.exists()&&!new File(file.getPath()+".bak").exists();
    }
    public static synchronized org.json.JSONArray list(Context c) {
        org.json.JSONArray result=new org.json.JSONArray();
        File[] files=new File(c.getFilesDir(),"dekk_personas").listFiles();
        if(files==null)return result;
        for(File f:files){
            if(!f.getName().matches("[0-9]+\\.json"))continue;
            try{
                FileInputStream in=new AtomicFile(f).openRead();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try{byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){
                    if(bytes.size()+n>200000)throw new Exception("too large");bytes.write(buf,0,n);
                }}finally{in.close();}
                result.put(new JSONObject(bytes.toString("UTF-8")));
            }catch(Exception ignored){ }
        }
        return result;
    }
}