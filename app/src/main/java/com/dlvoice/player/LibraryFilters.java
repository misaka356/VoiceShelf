package com.dlvoice.player;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;

/** One draft, one apply action; credit selection stays inside the same window. */
final class LibraryFilters extends Dialog {
    interface Source {
        int count(boolean favorites,String circle,String actor);
        Map<String,Integer> credits(boolean circles,boolean favorites,String circle,String actor);
        void apply(boolean favorites,String circle,String actor,int sort);
    }
    private final Source source;
    private final String scope;
    private boolean favorites,creditPage,circles;
    private String circle,actor;
    private int sort;
    private LinearLayout body,main,credit;
    private TextView title,creditHint;
    private Button back,apply;
    private EditText search;
    private ListView choices;
    private final List<String> shown=new ArrayList<>();
    private Map<String,Integer> counts=Collections.emptyMap();
    private static final int BG=0xff1b2030,CARD=0xff252c3e,INK=0xfff3f4fc,MUTED=0xffaeb7ce,ACCENT=0xffbec7ff,ON=0xff232b53;
    LibraryFilters(Context context,boolean fav,String c,String a,int order,String scope,Source source){
        super(context);this.source=source;this.scope=scope;
        favorites=fav;circle=c;actor=a;sort=order;
    }
    private int dp(int n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(1);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(getContext());v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView label(String text,int size,int color){TextView t=new TextView(getContext());t.setText(text);t.setTextSize(size);t.setTextColor(color);return t;}
    private Button action(String text,boolean active,Runnable click){Button b=new Button(getContext());b.setText(text);b.setTextSize(14);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setTextColor(active?ON:INK);b.setPadding(dp(10),0,dp(10),0);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff),shape(active?ACCENT:CARD,12),null));b.setOnClickListener(v->click.run());Motion.press(b);return b;}
    private void section(String name){TextView t=label(name,13,MUTED);t.setPadding(0,dp(20),0,dp(10));main.addView(t);}
    private void pair(LinearLayout parent,Button a,Button b){LinearLayout r=row();LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.rightMargin=dp(8);r.addView(a,p);r.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));parent.addView(r);}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);requestWindowFeature(Window.FEATURE_NO_TITLE);
        body=column();body.setPadding(dp(20),dp(16),dp(20),dp(12));body.setBackground(shape(BG,26));
        LinearLayout header=row();back=action("×",false,()->{if(creditPage)showMain();else dismiss();});header.addView(back,new LinearLayout.LayoutParams(dp(42),dp(42)));
        title=label(getContext().getString(R.string.text_filter_sort),21,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setPadding(dp(12),0,0,0);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button reset=action(getContext().getString(R.string.text_reset),false,()->{favorites=false;circle=actor="";sort=0;showMain();});header.addView(reset,new LinearLayout.LayoutParams(dp(64),dp(42)));body.addView(header);
        TextView scopeLabel=label(scope,12,MUTED);scopeLabel.setMaxLines(2);scopeLabel.setEllipsize(TextUtils.TruncateAt.END);scopeLabel.setPadding(0,dp(12),0,dp(2));body.addView(scopeLabel);
        FrameLayout stage=new FrameLayout(getContext());body.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(getContext());scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setPadding(0,0,0,dp(16));main=column();scroll.addView(main);stage.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        credit=column();credit.setVisibility(View.GONE);credit.setPadding(0,dp(16),0,dp(8));stage.addView(credit,new FrameLayout.LayoutParams(-1,-1));
        search=new EditText(getContext());search.setSingleLine(true);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setTextSize(15);search.setPadding(dp(14),0,dp(14),0);search.setBackground(shape(CARD,12));credit.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
        creditHint=label("",12,MUTED);creditHint.setPadding(dp(4),dp(10),0,dp(6));credit.addView(creditHint);
        choices=new ListView(getContext());choices.setDivider(null);credit.addView(choices,new LinearLayout.LayoutParams(-1,0,1));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){refreshChoices();}public void afterTextChanged(Editable e){}});
        choices.setOnItemClickListener((p,v,i,id)->{String value=shown.get(i);if(circles)circle=value;else actor=value;showMain();});
        View divider=new View(getContext());divider.setBackgroundColor(0xff343c52);body.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout footer=row();footer.setPadding(0,dp(12),0,0);
        Button cancel=action(getContext().getString(R.string.text_cancel),false,this::dismiss);LinearLayout.LayoutParams cancelSize=new LinearLayout.LayoutParams(dp(80),dp(48));cancelSize.rightMargin=dp(10);footer.addView(cancel,cancelSize);
        apply=action(getContext().getString(R.string.text_apply),true,()->{source.apply(favorites,circle,actor,sort);dismiss();});footer.addView(apply,new LinearLayout.LayoutParams(0,dp(48),1));body.addView(footer);
        setContentView(body);showMain();
    }
    @Override protected void onStart(){super.onStart();Window w=getWindow();if(w!=null){Motion.window(w,R.style.MotionPanel);w.setBackgroundDrawableResource(android.R.color.transparent);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.6f);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);android.util.DisplayMetrics m=getContext().getResources().getDisplayMetrics();w.setLayout(Math.min(dp(560),m.widthPixels-dp(28)),Math.min(dp(660),(int)(m.heightPixels*.86f)));}}
    private void showMain(){
        boolean returning = creditPage;
        View mainPane = (View) main.getParent();
        creditPage=false;credit.setVisibility(View.GONE);mainPane.setVisibility(View.VISIBLE);title.setText(getContext().getString(R.string.text_filter_sort));back.setText("×");
        ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);search.clearFocus();
        main.removeAllViews();section(getContext().getString(R.string.text_work_scope));pair(main,action(getContext().getString(R.string.text_all_works),!favorites,()->{favorites=false;showMain();}),action(getContext().getString(R.string.text_favorites_only),favorites,()->{favorites=true;showMain();}));
        section(getContext().getString(R.string.text_work_information_match_all_conditions));creditRow(getContext().getString(R.string.text_circle),circle,true);creditRow(getContext().getString(R.string.text_cast),actor,false);
        section(getContext().getString(R.string.text_sort_by));LinearLayout keys=row();String[] names={getContext().getString(R.string.text_rj_id),getContext().getString(R.string.text_date_added),getContext().getString(R.string.text_date_modified)};for(int i=0;i<3;i++){final int key=i;Button b=action(names[i],sort%3==i,()->{sort=key+(sort>=3?3:0);showMain();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);if(i<2)p.rightMargin=dp(6);keys.addView(b,p);}main.addView(keys);
        TextView gap=label("",1,MUTED);main.addView(gap,new LinearLayout.LayoutParams(-1,dp(8)));
        int key=sort%3;pair(main,action(key==0?getContext().getString(R.string.text_id_ascending):getContext().getString(R.string.text_newest_first),sort<3,()->{sort=key;showMain();}),action(key==0?getContext().getString(R.string.text_id_descending):getContext().getString(R.string.text_oldest_first),sort>=3,()->{sort=key+3;showMain();}));
        apply.setText(getContext().getString(R.string.text_apply_2)+source.count(favorites,circle,actor)+getContext().getString(R.string.text_works));
        if (returning && Motion.enabled(getContext())) Motion.enter(mainPane, -dp(28), 0, .64f, 365).start();
    }
    private void creditRow(String name,String value,boolean isCircle){
        LinearLayout card=row();card.setPadding(dp(14),dp(10),dp(8),dp(10));card.setBackground(shape(CARD,14));LinearLayout labels=column();TextView heading=label(name,12,MUTED);labels.addView(heading);TextView selected=label(value.isEmpty()?getContext().getString(R.string.text_all) :value,16,INK);selected.setMaxLines(2);selected.setEllipsize(TextUtils.TruncateAt.END);selected.setPadding(0,dp(4),0,0);labels.addView(selected);card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Button open=action("›",false,()->showCredits(isCircle));card.addView(open,new LinearLayout.LayoutParams(dp(42),dp(44)));card.setOnClickListener(v->showCredits(isCircle));
        if(!value.isEmpty()){Button clear=action("×",false,()->{if(isCircle)circle="";else actor="";showMain();});card.addView(clear,new LinearLayout.LayoutParams(dp(42),dp(44)));}
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);main.addView(card,p);
    }
    private void showCredits(boolean isCircle){
        circles=isCircle;counts=source.credits(circles,favorites,circle,actor);creditPage=true;((View)main.getParent()).setVisibility(View.GONE);credit.setVisibility(View.VISIBLE);title.setText(isCircle?getContext().getString(R.string.text_choose_circle):getContext().getString(R.string.text_choose_cast_member));back.setText("‹");search.setHint(isCircle?getContext().getString(R.string.text_search_circles):getContext().getString(R.string.text_search_cast));search.setText("");refreshChoices();
        if (Motion.enabled(getContext())) Motion.enter(credit, dp(28), 0, .64f, 365).start();
    }
    private void refreshChoices(){
        if(choices==null||!creditPage)return;
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);shown.clear();shown.add("");
        List<String> names=new ArrayList<>(counts.keySet());names.sort(java.text.Collator.getInstance(Locale.JAPANESE));
        String selected=circles?circle:actor;if(!selected.isEmpty()&&!names.contains(selected))names.add(0,selected);
        for(String name:names)if(name.toLowerCase(Locale.ROOT).contains(query))shown.add(name);
        creditHint.setText(shown.size()==1&&!query.isEmpty()?getContext().getString(R.string.text_no_matching_names):getContext().getString(R.string.text_available)+(shown.size()-1)+getContext().getString(R.string.text_count_suffix)+(circles?getContext().getString(R.string.text_circle):getContext().getString(R.string.text_cast))+getContext().getString(R.string.text_counts_reflect_other_filters));
        choices.setAdapter(new BaseAdapter(){public int getCount(){return shown.size();}public Object getItem(int i){return shown.get(i);}public long getItemId(int i){return i;}
            public View getView(int i,View recycled,ViewGroup parent){String value=shown.get(i);boolean chosen=value.equals(circles?circle:actor);LinearLayout row=row();row.setPadding(dp(14),dp(14),dp(14),dp(14));row.setMinimumHeight(dp(60));row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff),shape(chosen?0xff303957:BG,12),null));TextView text=label(value.isEmpty()?(circles?getContext().getString(R.string.text_all_circles):getContext().getString(R.string.text_all_cast_members)):value,16,chosen?ACCENT:INK);text.setMaxLines(3);text.setEllipsize(TextUtils.TruncateAt.END);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));int count=value.isEmpty()?source.count(favorites,circles?"":circle,circles?actor:""):counts.getOrDefault(value,0);TextView badge=label((chosen?"✓  ":"")+count,13,chosen?ACCENT:MUTED);badge.setPadding(dp(12),0,0,0);row.addView(badge);return row;}});
    }
    @Override public void onBackPressed(){if(creditPage)showMain();else super.onBackPressed();}
}
