package com.dlvoice.player;

import android.app.AlertDialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.List;

/** Shared, scrollable dialogs with fixed actions and generous touch targets. */
final class AppDialog extends AlertDialog {
    private static final int SURFACE=0xff1b2030, INK=0xfff3f4fc, MUTED=0xffaeb7ce, ACCENT=0xffbec7ff;
    private AppDialog(Context context){super(context);}
    private static int dp(Context c,int n){return Math.round(c.getResources().getDisplayMetrics().density*n);}
    private static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    @Override protected void onStart(){
        super.onStart();
        Window w=getWindow();
        if(w!=null){Motion.window(w,R.style.MotionPanel);w.setBackgroundDrawable(shape(getContext(),SURFACE,24));w.setDimAmount(.6f);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.setLayout(Math.min(dp(getContext(),560),getContext().getResources().getDisplayMetrics().widthPixels-dp(getContext(),32)),WindowManager.LayoutParams.WRAP_CONTENT);}
        for(int id:new int[]{BUTTON_POSITIVE,BUTTON_NEGATIVE,BUTTON_NEUTRAL}){
            Button b=getButton(id);if(b==null||b.getVisibility()!=View.VISIBLE)continue;
            boolean primary=id==BUTTON_POSITIVE;b.setTextColor(primary?0xff232b53:id==BUTTON_NEGATIVE?MUTED:ACCENT);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(getContext(),46));b.setMinWidth(dp(getContext(),64));b.setPadding(dp(getContext(),16),0,dp(getContext(),16),0);
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff),shape(getContext(),primary?ACCENT:0x00000000,12),null));
            Motion.press(b);
            if(b.getLayoutParams() instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams params=(ViewGroup.MarginLayoutParams)b.getLayoutParams();params.leftMargin=dp(getContext(),4);params.rightMargin=dp(getContext(),4);params.bottomMargin=dp(getContext(),8);b.setLayoutParams(params);}
        }
    }
    private static void style(View view){
        Context c=view.getContext();
        if(view instanceof EditText){EditText input=(EditText)view;input.setMinHeight(dp(c,52));input.setTextColor(INK);input.setHintTextColor(MUTED);input.setTextSize(16);input.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));input.setBackground(shape(c,0xff272e42,12));input.setSelectAllOnFocus(true);if(input.getLayoutParams() instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)input.getLayoutParams();lp.bottomMargin=dp(c,10);input.setLayoutParams(lp);}}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)style(((ViewGroup)view).getChildAt(i));
    }
    private static final class Body extends ScrollView{
        Body(Context c){super(c);setFillViewport(false);setClipToPadding(false);}
        @Override protected void onMeasure(int width,int height){int cap=(int)(getResources().getDisplayMetrics().heightPixels*.55f);super.onMeasure(width,MeasureSpec.makeMeasureSpec(MeasureSpec.getMode(height)==MeasureSpec.UNSPECIFIED?cap:Math.min(cap,MeasureSpec.getSize(height)),MeasureSpec.AT_MOST));}
    }
    private static final class MenuList extends ListView{
        MenuList(Context c){super(c);}
        @Override protected void onMeasure(int width,int height){int cap=(int)(getResources().getDisplayMetrics().heightPixels*.53f);super.onMeasure(width,MeasureSpec.makeMeasureSpec(MeasureSpec.getMode(height)==MeasureSpec.UNSPECIFIED?cap:Math.min(cap,MeasureSpec.getSize(height)),MeasureSpec.AT_MOST));}
    }
    private static final class SelectionMark extends android.graphics.drawable.Drawable{
        final Context context;final boolean checked;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        SelectionMark(Context c,boolean checked){context=c;this.checked=checked;}
        public int getIntrinsicWidth(){return dp(context,28);}public int getIntrinsicHeight(){return dp(context,22);}
        public void draw(Canvas canvas){android.graphics.Rect b=getBounds();float x=b.exactCenterX(),y=b.exactCenterY();paint.setColor(checked?ACCENT:MUTED);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(context,2));canvas.drawCircle(x,y,dp(context,9),paint);if(checked){paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,dp(context,4),paint);}}
        public void setAlpha(int value){paint.setAlpha(value);}public void setColorFilter(ColorFilter value){paint.setColorFilter(value);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    static final class Builder {
        private final Context context;
        private CharSequence title,message,positive,negative,neutral;
        private View view;
        private CharSequence[] items;
        private int checked=-1;
        private boolean single;
        private DialogInterface.OnClickListener itemClick,positiveClick,negativeClick,neutralClick;
        Builder(Context c){context=c;}
        Builder setTitle(CharSequence v){title=v;return this;}
        Builder setMessage(CharSequence v){message=v;return this;}
        Builder setView(View v){view=v;return this;}
        Builder setItems(CharSequence[] v,DialogInterface.OnClickListener listener){items=v;itemClick=listener;return this;}
        Builder setSingleChoiceItems(CharSequence[] v,int index,DialogInterface.OnClickListener listener){items=v;checked=index;single=true;itemClick=listener;return this;}
        Builder setPositiveButton(CharSequence v,DialogInterface.OnClickListener listener){positive=v;positiveClick=listener;return this;}
        Builder setNegativeButton(CharSequence v,DialogInterface.OnClickListener listener){negative=v;negativeClick=listener;return this;}
        Builder setNeutralButton(CharSequence v,DialogInterface.OnClickListener listener){neutral=v;neutralClick=listener;return this;}
        AppDialog create(){
            AppDialog dialog=new AppDialog(context);
            LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(context,12),dp(context,8),dp(context,12),dp(context,8));
            if(title!=null){TextView heading=new TextView(context);heading.setText(title);heading.setTextColor(INK);heading.setTextSize(21);heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);heading.setMaxLines(5);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.setPadding(dp(context,12),dp(context,14),dp(context,12),dp(context,18));content.addView(heading);}
            if(items!=null){
                ListView list=new MenuList(context);list.setDivider(null);list.setClipToPadding(false);list.setPadding(0,0,0,dp(context,4));
                list.setAdapter(new BaseAdapter(){
                    public int getCount(){return items.length;}public Object getItem(int i){return items[i];}public long getItemId(int i){return i;}
                    public View getView(int i,View recycled,ViewGroup parent){
                        CheckedTextView row=recycled instanceof CheckedTextView?(CheckedTextView)recycled:new CheckedTextView(context);
                        String value=String.valueOf(items[i]);int split=value.indexOf("  ·  ");
                        if(split>=0)value=value.substring(0,split)+"\n"+value.substring(split+5);
                        android.text.SpannableString display=new android.text.SpannableString(value);int line=value.indexOf('\n');
                        if(line>=0){display.setSpan(new android.text.style.ForegroundColorSpan(MUTED),line+1,value.length(),0);display.setSpan(new android.text.style.RelativeSizeSpan(.85f),line+1,value.length(),0);}
                        row.setText(display);row.setTextSize(16);row.setTextColor(INK);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinHeight(dp(context,56));row.setPadding(dp(context,14),dp(context,14),dp(context,14),dp(context,14));row.setLineSpacing(dp(context,3),1);
                        if(single){row.setCheckMarkDrawable(new SelectionMark(context,i==checked));row.setChecked(i==checked);}else row.setCheckMarkDrawable((android.graphics.drawable.Drawable)null);
                        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35bec7ff),shape(context,i==checked?0xff303957:SURFACE,12),null));return row;
                    }
                });
                if(single){list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);list.setItemChecked(checked,true);if(checked>=0)list.setSelection(Math.max(0,checked-2));}
                list.setOnItemClickListener((parent,row,index,id)->{if(itemClick==null)return;if(single){checked=index;((BaseAdapter)list.getAdapter()).notifyDataSetChanged();}itemClick.onClick(dialog,index);if(!single)dialog.dismiss();});
                content.addView(list,new LinearLayout.LayoutParams(-1,-2));
            }else if(view!=null){
                style(view);
                if(containsList(view)){content.addView(view,new LinearLayout.LayoutParams(-1,-2));}
                else{Body scroll=new Body(context);scroll.addView(view);content.addView(scroll,new LinearLayout.LayoutParams(-1,-2));}
            }else if(message!=null){TextView text=new TextView(context);text.setText(message);text.setTextColor(MUTED);text.setTextSize(16);text.setPadding(dp(context,12),0,dp(context,12),dp(context,12));text.setLineSpacing(dp(context,5),1);Body scroll=new Body(context);scroll.addView(text);content.addView(scroll);}
            dialog.setView(content,0,0,0,0);
            if(positive!=null)dialog.setButton(BUTTON_POSITIVE,positive,positiveClick);
            if(negative!=null)dialog.setButton(BUTTON_NEGATIVE,negative,negativeClick);
            if(neutral!=null)dialog.setButton(BUTTON_NEUTRAL,neutral,neutralClick);
            return dialog;
        }
        AppDialog show(){AppDialog d=create();d.show();return d;}
        private boolean containsList(View v){if(v instanceof AbsListView)return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(containsList(((ViewGroup)v).getChildAt(i)))return true;return false;}
    }
}
