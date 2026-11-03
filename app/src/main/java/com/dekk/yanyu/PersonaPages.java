package com.dekk.yanyu;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;

/** 人设市场 / 我上传的 / 已下载：Miuix 分段标签 + 搜索框 + 列表卡片。 */
public final class PersonaPages {
    private final Activity a;
    private final CenterPage p;
    private final String mode;
    private final LinearLayout results;
    private final TextView more;
    private EditText search;
    private String query="";
    private int page=1,generation=0;
    private boolean busy;
    /** 首屏骨架的张数（用于把真实卡片插到对应骨架的位置上）。 */
    private int skeletonCount=0;
    /** 底部状态区（加载圆点 / 没有更多了 / 重试）。 */
    private LinearLayout footer;
    /** 加载中圆点。 */
    private View dots;

    public static void show(Activity a,String mode){show(a,mode,-1);}

    /**
     * @param fromIndex 上一个页签索引；用来决定内容从哪一侧推入（-1 = 首次进入不做推入）
     */
    public static void show(Activity a,String mode,int fromIndex){new PersonaPages(a,mode,fromIndex);}

    private static String titleOf(String type){
        return type.equals("mine")?"我上传的":type.equals("local")?"已下载":"人设市场";
    }
    private static int indexOf(String type){
        return type.equals("mine")?1:type.equals("local")?2:0;
    }

    private PersonaPages(final Activity activity,String type,final int fromIndex){
        a=activity;mode=type;
        p=new CenterPage(a,titleOf(type),null).navigationKey("persona:"+type);
        // 从页签切过来的：同层切换，不做"进入新一层"的动画，避免和 Tab 胶囊 + 内容推入打架
        if(fromIndex>=0)p.noEnterAnimation();

        LinearLayout tabCard=p.card();
        tabCard.setPadding(p.dp(6),p.dp(6),p.dp(6),p.dp(6));
        final MiuixUi.Tabs strip=MiuixUi.tabs(a,p.night,new String[]{"市场","我的上传","已下载"},indexOf(type),
                new MiuixUi.TabListener(){public void onSelected(int index){
                    if(index==1&&AccountStore.token(a).length()==0){AccountPages.open(a);return;}
                    String target=index==0?"market":index==1?"mine":"local";
                    if(target.equals(mode))return;
                    // 带上当前页签索引：新页面会据此决定内容从哪一侧推入
                    show(a,target,indexOf(mode));
                }});
        tabCard.addView(strip.view);
        // 从别的页签切进来的：先停在上一个页签位置，下一帧再让胶囊流到当前项
        // （页面是重建的，不补这一下就看不到水流效果）
        if(fromIndex>=0&&fromIndex<strip.items.length){
            MiuixUi.jumpTo(strip,p.night,fromIndex);
            final int targetIndex=indexOf(type);
            strip.view.postDelayed(new Runnable(){public void run(){
                if(!p.active())return;
                MiuixUi.select(strip,p.night,targetIndex);
            }},70L);
        }

        LinearLayout searchCard=p.card();
        searchCard.setPadding(p.dp(16),p.dp(14),p.dp(16),p.dp(14));
        LinearLayout searchRow=new LinearLayout(a);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        search=MiuixUi.searchField(a,p.night,"搜索人设名称");
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchRow.addView((View)search.getParent(),new LinearLayout.LayoutParams(0,p.dp(46),1f));
        TextView go=MiuixUi.smallButton(a,p.night,"搜索",true,new Runnable(){public void run(){reload();}});
        LinearLayout.LayoutParams goParams=new LinearLayout.LayoutParams(-2,-2);
        goParams.leftMargin=p.dp(10);
        searchRow.addView(go,goParams);
        searchCard.addView(searchRow);
        search.setOnEditorActionListener(new TextView.OnEditorActionListener(){
            public boolean onEditorAction(TextView v,int id,android.view.KeyEvent e){
                if(id==EditorInfo.IME_ACTION_SEARCH){reload();return true;}
                return false;
            }});

        if(type.equals("mine")){
            LinearLayout upload=p.card();
            p.rowIcon(upload,MiuixIcon.DOC,"上传人设 ›",new Runnable(){public void run(){PersonaEditor.show(a,null);}});
        }

        results=new LinearLayout(a);
        results.setOrientation(1);
        p.body.addView(results,new LinearLayout.LayoutParams(-1,-2));

        // 底部状态区：加载圆点 / 「没有更多了」/「重试」
        footer=new LinearLayout(a);
        footer.setOrientation(1);
        p.body.addView(footer,new LinearLayout.LayoutParams(-1,-2));

        // 「加载更多」按钮保留作为兜底（出错时可手动重试），平时隐藏；
        // 正常使用靠滚动预加载，不再需要用户点。
        more=p.action("加载更多",new Runnable(){public void run(){load();}});
        more.setVisibility(View.GONE);

        p.show();
        reload();
        // 切页签进来的：列表内容沿点击方向轻推入
        if(fromIndex>=0)FeedUi.pushIn(results,indexOf(mode)>fromIndex?1:-1);
        // 滑到离底部约一屏时提前加载下一页
        FeedUi.attachPreload(results,new Runnable(){public void run(){load();}});
    }

    private void reload(){
        String q=search.getText().toString().trim();
        if(q.length()>100){p.message("名称搜索最多100字");return;}
        query=q;generation++;busy=false;page=1;
        skeletonCount=0;
        results.removeAllViews();
        footer.removeAllViews();
        if(mode.equals("local")){
            more.setVisibility(View.GONE);
            JSONArray all=PersonaLocalStore.list(a),filtered=new JSONArray();
            for(int i=0;i<all.length();i++){
                JSONObject r=all.optJSONObject(i);
                if(r!=null && r.optString("name").toLowerCase(java.util.Locale.ROOT)
                        .contains(query.toLowerCase(java.util.Locale.ROOT)))filtered.put(r);
            }
            render(filtered,false);
            if(filtered.length()==0)empty("暂无匹配的本地人设");
        }else{
            more.setVisibility(View.GONE);
            // 首屏：先铺与真实卡片同形的骨架，底部显示跳动圆点
            FeedUi.showSkeletons(results,a,p,6);
            skeletonCount=6;
            showDots();
            load();
        }
    }

    /** 底部：三个依次跳动的小圆点（正在加载）。 */
    private void showDots(){
        footer.removeAllViews();
        dots=FeedUi.dots(a,p,p.night);
        footer.addView(dots,new LinearLayout.LayoutParams(-1,-2));
    }

    /** 底部：没有更多了。 */
    private void showEnd(){
        footer.removeAllViews();
        dots=null;
        footer.addView(FeedUi.footerText(a,p,"没有更多了"),new LinearLayout.LayoutParams(-1,-2));
    }

    /** 底部：出错时给一个可点的重试。 */
    private void showRetry(final String error){
        footer.removeAllViews();
        dots=null;
        TextView retry=FeedUi.footerText(a,p,error+"（点击重试）");
        retry.setOnClickListener(new View.OnClickListener(){public void onClick(View v){showDots();load();}});
        footer.addView(retry,new LinearLayout.LayoutParams(-1,-2));
    }

    private void empty(String s){
        TextView t=p.label(s,14);
        t.setTextColor(p.secondary);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0,p.dp(32),0,p.dp(32));
        results.addView(t);
    }

    private void load(){
        if(busy)return;
        busy=true;more.setEnabled(false);
        final int ticket=generation,requestedPage=page;
        PersonaApi.request(a,mode+"&page="+requestedPage+"&search="+android.net.Uri.encode(query),null,
                new PersonaApi.Callback(){public void done(JSONObject r,String e){
            if(!p.active() || ticket!=generation)return;
            busy=false;more.setEnabled(true);
            if(e!=null){showRetry(e);return;}
            JSONArray rows=r.optJSONArray("items");
            if(rows==null){showRetry("列表响应格式错误");return;}

            boolean firstPage=requestedPage==1;
            render(rows,firstPage);
            if(firstPage && rows.length()==0){
                results.removeAllViews();skeletonCount=0;footer.removeAllViews();
                empty("没有找到匹配的人设");
                return;
            }
            page=requestedPage+1;
            if(rows.length()<20){
                // 最后一页
                showEnd();
            }else if(!firstPage || rows.length()>0){
                // 还有下一页：不显示"没有更多了"，滑到底会自动续上；
                // 加载动画（圆点）在本次数据落地后收起，避免一直闪。
                if(dots!=null){footer.removeAllViews();dots=null;}
            }
        }});
    }

    /** 列表卡片：圆头像 + 标题 17 + 简介 13 + 作者 13，底部右对齐操作按钮。 */
    private void render(JSONArray rows,boolean firstPage){
        // 首屏：先把当前铺着的骨架抓出来，等会儿真卡要"长"在骨架的位置上
        java.util.List<View> skeletons=new java.util.ArrayList<View>();
        java.util.Set<View> usedSkeletons=new java.util.HashSet<View>();
        if(firstPage){
            for(int k=0;k<skeletonCount && k<results.getChildCount();k++)skeletons.add(results.getChildAt(k));
        }
        for(int i=0;i<rows.length();i++){
            final JSONObject role=rows.optJSONObject(i);
            if(role==null)continue;
            LinearLayout card=MiuixUi.card(a,p.night);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.bottomMargin=p.dp(14);

            // ① 头部：圆头像 +（标题 / 简介）
            LinearLayout head=new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.TOP);
            head.setPadding(p.dp(16),p.dp(18),p.dp(16),0);

            ImageView avatar=new ImageView(a);
            String avatarUrl=FeedUi.authorAvatarUrl(role);
            if(avatarUrl!=null)AvatarUi.show(avatar,avatarUrl);
            else avatar.setImageDrawable(AvatarUi.defaultDrawable(a));
            LinearLayout.LayoutParams avatarParams=new LinearLayout.LayoutParams(p.dp(FeedUi.AVATAR_DP),p.dp(FeedUi.AVATAR_DP));
            head.addView(avatar,avatarParams);

            LinearLayout texts=new LinearLayout(a);
            texts.setOrientation(1);
            LinearLayout.LayoutParams textsParams=new LinearLayout.LayoutParams(0,-2,1f);
            textsParams.leftMargin=p.dp(12);
            head.addView(texts,textsParams);
            TextView title=p.label(role.optString("name"),17);
            title.setTypeface(MiuixUi.medium());
            texts.addView(title);
            TextView desc=p.label(role.optString("description"),13);
            desc.setTextColor(p.secondary);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            desc.setPadding(0,p.dp(6),0,0);
            texts.addView(desc);
            card.addView(head);

            // ② 作者
            LinearLayout byline=new LinearLayout(a);
            byline.setOrientation(LinearLayout.HORIZONTAL);
            TextView by=p.label("作者：",13);
            by.setTextColor(p.secondary);
            byline.addView(by);
            TextView author=p.label(role.optString("author"),13);
            author.setTextColor(p.secondary);
            SponsorName.apply(author,SponsorName.flag(role,"author_sponsor"),p.secondary);
            byline.addView(author);
            byline.setPadding(p.dp(16),p.dp(12),p.dp(16),0);
            card.addView(byline);

            // ③ 底部操作按钮
            LinearLayout actions=new LinearLayout(a);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            actions.setPadding(p.dp(16),p.dp(12),p.dp(16),p.dp(12));

            if(mode.equals("local")){
                TextView use=MiuixUi.smallButton(a,p.night,"使用",true,new Runnable(){public void run(){
                    PersonaSessionStore.bind(a,role);
                    p.message("已选择人设，下一次文本请求将附加。当前版本尚未自动创建新会话。");
                }});
                actions.addView(use,buttonParams(0));
                TextView remove=MiuixUi.smallButton(a,p.night,"删除本地副本",false,new Runnable(){public void run(){
                    new ModuleDialog(a).setTitle("删除已下载人设")
                        .setMessage("删除「"+role.optString("name")+"」的本机副本？不会删除服务器上的人设。")
                        .setNegativeButton("取消",null)
                        .setPositiveButton("删除",new DialogInterface.OnClickListener(){
                            public void onClick(DialogInterface d,int which){
                                if(PersonaLocalStore.delete(a,role.optLong("id"))){reload();p.message("已删除本地副本");}
                                else p.message("删除失败，请重试");
                            }
                        }).show();
                }});
                actions.addView(remove,buttonParams(8));
            }else{
                final TextView[] download=new TextView[1];
                download[0]=MiuixUi.smallButton(a,p.night,"下载",true,new Runnable(){public void run(){
                    Motion.busy(download[0],true); // 下载中：按钮呼吸 + 禁用
                    fetch(role,false,download[0]);
                }});
                actions.addView(download[0],buttonParams(0));
                if(mode.equals("mine")){
                    TextView manage=MiuixUi.smallButton(a,p.night,
                            role.optString("visibility").equals("public")?"公开 · 编辑":"私密 · 编辑",false,
                            new Runnable(){public void run(){fetch(role,true,null);}});
                    actions.addView(manage,buttonParams(8));
                    actions.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
                }
                card.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
                    ItemDetailPage.show(a,ItemDetailPage.PERSONA,role);
                }});
                // 卡片按下时轻微回缩：点起来"跟手"
                Motion.pressable(card,Motion.ROW_PRESS_SCALE);
            }
            card.addView(actions);

            // ④ 落位 + 入场动画
            View skeleton=(firstPage && i<skeletons.size())?skeletons.get(i):null;
            if(skeleton!=null && skeleton.getParent()==results){
                // 真卡插到骨架所在位置，然后骨架淡出、卡内各块从上到下错开浮现
                usedSkeletons.add(skeleton);
                results.addView(card,results.indexOfChild(skeleton),lp);
                FeedUi.revealBlocks(card,skeleton,true);
            }else{
                results.addView(card,lp);
                // 只有"分页追加"才做传送带式入场；本地列表直接显示
                FeedUi.enterCard(card,i,!firstPage && !mode.equals("local"));
            }
        }
        if(firstPage){
            // 数据不足骨架张数时（例如只有 3 条），多余的骨架必须跟着消失，
            // 否则底部会残留几块灰色占位，看起来像"还在自动加载"。
            FeedUi.dropUnusedSkeletons(skeletons,usedSkeletons);
            skeletonCount=0;
        }
    }

    private LinearLayout.LayoutParams buttonParams(int leftDp){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);
        params.leftMargin=p.dp(leftDp);
        return params;
    }

    private void fetch(JSONObject role,final boolean edit,final TextView button){
        PersonaApi.request(a,"download&id="+role.optLong("id"),null,new PersonaApi.Callback(){
            public void done(JSONObject r,String e){
                if(!p.active())return;
                if(button!=null){Motion.stopBreath(button);button.setEnabled(true);}
                if(e!=null){p.message(e);return;}
                JSONObject item=r.optJSONObject("persona");
                if(item==null)return;
                if(edit){PersonaEditor.show(a,item);return;}
                try{
                    PersonaLocalStore.save(a,item);
                    if(button!=null){button.setText("已下载");Motion.pulse(button);} // 完成脉冲
                    p.message("已保存，可在聊天里使用");
                }catch(Exception ex){p.message("本地保存失败");}
            }});
    }
}