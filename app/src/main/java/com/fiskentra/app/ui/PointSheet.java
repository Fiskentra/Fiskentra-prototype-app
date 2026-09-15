package com.fiskentra.app.ui;
import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.fiskentra.app.R;
import com.fiskentra.app.model.*;
import com.fiskentra.app.location.FiskentraLocationManager;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

final class PointSheet extends LinearLayout implements MapSheet.Expandable,MapSheet.FooterContent {
    interface Actions{void navigate();void edit();void details();void favorite();void more();}
    private final TextView title,info,details,notes,distance,status,photoError,weight,length,weightLabel,lengthLabel;
    private final ImageView photo;
    private final ImageButton favorite;
    private final Button navigate,edit,detailAction;
    private final LinearLayout media,metrics,footer,actionRow,secondary;
    private boolean sideBySide,largeActions;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService images=Executors.newSingleThreadExecutor();
    private int generation;
    private String photoPath="";
    private Bitmap bitmap;
    private boolean closed;
    private boolean expanded,photoFailed;
    PointSheet(Context c,Actions actions){super(c);setOrientation(VERTICAL);
        LinearLayout head=new LinearLayout(c);head.setGravity(Gravity.CENTER_VERTICAL);title=MapUi.text(c,"",22,true);head.addView(title,new LayoutParams(0,-2,1));favorite=new ImageButton(c);favorite.setImageResource(android.R.drawable.btn_star_big_off);favorite.setScaleType(ImageView.ScaleType.FIT_CENTER);favorite.setPadding(MapUi.dp(c,10),MapUi.dp(c,10),MapUi.dp(c,10),MapUi.dp(c,10));favorite.setContentDescription(c.getString(R.string.map_favorite));favorite.setOnClickListener(v->actions.favorite());favorite.setBackgroundColor(Color.TRANSPARENT);head.addView(favorite,new LayoutParams(MapUi.dp(c,48),MapUi.dp(c,48)));Button more=MapUi.button(c,R.string.map_more,actions::more,false);more.setText("⋮");more.setTextSize(24);more.setContentDescription(c.getString(R.string.map_more));more.setBackgroundColor(Color.TRANSPARENT);head.addView(more,new LayoutParams(MapUi.dp(c,48),MapUi.dp(c,48)));addView(head);
        info=MapUi.text(c,"",13,false);info.setTextColor(0xffaac5e4);addView(info);
        media=new LinearLayout(c);media.setGravity(Gravity.CENTER_VERTICAL);media.setPadding(0,MapUi.dp(c,8),0,MapUi.dp(c,8));addView(media,new LayoutParams(-1,-2));
        photo=new ImageView(c);photo.setScaleType(ImageView.ScaleType.CENTER_CROP);photo.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.graphics.drawable.GradientDrawable photoShape=new android.graphics.drawable.GradientDrawable();photoShape.setColor(0xff0d3548);photoShape.setCornerRadius(MapUi.dp(c,10));photo.setBackground(photoShape);photo.setClipToOutline(true);media.addView(photo);photo.setVisibility(GONE);
        metrics=MapUi.column(c);media.addView(metrics);details=MapUi.text(c,"",15,true);metrics.addView(details);
        weight=MapUi.text(c,"",26,true);metrics.addView(weight);weightLabel=MapUi.text(c,c.getString(R.string.data_weight_label),13,false);weightLabel.setTextColor(0xffaac5e4);metrics.addView(weightLabel);
        length=MapUi.text(c,"",26,true);metrics.addView(length);lengthLabel=MapUi.text(c,c.getString(R.string.data_length_label),13,false);lengthLabel.setTextColor(0xffaac5e4);metrics.addView(lengthLabel);
        photoError=MapUi.text(c,c.getString(R.string.map_no_photo),13,false);addView(photoError);photoError.setVisibility(GONE);
        notes=MapUi.text(c,"",13,false);addView(notes);distance=MapUi.text(c,"",13,false);distance.setTextColor(0xffaac5e4);status=MapUi.text(c,"",13,false);status.setTextColor(0xffaac5e4);
        footer=MapUi.column(c);footer.setPadding(0,MapUi.dp(c,4),0,0);footer.addView(distance);footer.addView(status);
        actionRow=new LinearLayout(c);actionRow.setGravity(Gravity.CENTER_VERTICAL);actionRow.setPadding(0,MapUi.dp(c,6),0,0);footer.addView(actionRow,new LayoutParams(-1,-2));secondary=new LinearLayout(c);secondary.setGravity(Gravity.CENTER_VERTICAL);
        navigate=MapUi.button(c,R.string.map_navigate,actions::navigate,true);navigate.setMinHeight(MapUi.dp(c,56));edit=MapUi.button(c,R.string.map_edit,actions::edit,false);detailAction=MapUi.button(c,R.string.map_details,actions::details,false);
        actionRow.addView(navigate);actionRow.addView(secondary);secondary.addView(edit);secondary.addView(detailAction);configureLayout(getResources().getDisplayMetrics().widthPixels);
    }
    @Override public View footer(){return footer;}
    @Override public void prepareWidth(int widthPx){boolean wide=widthPx>=MapUi.dp(getContext(),300)&&getResources().getConfiguration().fontScale<=1.3f;if(sideBySide!=wide||largeActions==wide)configureLayout(widthPx);}
    private void configureLayout(int widthPx){
        boolean wide=widthPx>=MapUi.dp(getContext(),300)&&getResources().getConfiguration().fontScale<=1.3f;
        sideBySide=wide;largeActions=!wide;
        media.setOrientation(wide?HORIZONTAL:VERTICAL);
        photo.setLayoutParams(wide?new LayoutParams(0,MapUi.dp(getContext(),142),1.5f):new LayoutParams(-1,MapUi.dp(getContext(),164)));
        LayoutParams metricParams=wide?new LayoutParams(0,-2,1):new LayoutParams(-1,-2);metricParams.setMargins(wide?MapUi.dp(getContext(),12):0,wide?0:MapUi.dp(getContext(),6),0,0);metrics.setLayoutParams(metricParams);
        actionRow.setOrientation(wide?HORIZONTAL:VERTICAL);
        navigate.setLayoutParams(wide?new LayoutParams(0,-2,1.3f):new LayoutParams(-1,-2));
        LayoutParams secondParams=wide?new LayoutParams(0,-2,2):new LayoutParams(-1,-2);secondParams.setMargins(wide?MapUi.dp(getContext(),6):0,wide?0:MapUi.dp(getContext(),6),0,0);secondary.setLayoutParams(secondParams);
        LayoutParams editParams=new LayoutParams(0,-2,1);editParams.setMargins(0,0,MapUi.dp(getContext(),6),0);edit.setLayoutParams(editParams);detailAction.setLayoutParams(new LayoutParams(0,-2,1));
    }
    @Override protected void onMeasure(int width,int height){prepareWidth(MeasureSpec.getSize(width));super.onMeasure(width,height);}
    void bind(SavedPoint p,Location fix,boolean imperial,String syncStatus){
        title.setText(p.title.isEmpty()?p.type:p.title);info.setText(p.type+" · "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(p.timestamp)));
        CatchDetails c=p.catchDetails;details.setText(c==null?"":c.species);
        weight.setText(c!=null&&c.weightKg>0?String.format(Locale.getDefault(),imperial?"%.1f lb":"%.1f kg",imperial?c.weightKg*2.2046226218:c.weightKg):"");
        length.setText(c!=null&&c.lengthCm>0?String.format(Locale.getDefault(),imperial?"%.1f in":"%.0f cm",imperial?c.lengthCm/2.54:c.lengthCm):"");
        weight.setVisibility(weight.length()>0?VISIBLE:GONE);weightLabel.setVisibility(weight.getVisibility());length.setVisibility(length.length()>0?VISIBLE:GONE);lengthLabel.setVisibility(length.getVisibility());
        notes.setText(((c==null||c.notes.isEmpty()?"":c.notes+"\n")+p.note).trim());
        boolean hasLocation=p.hasLocation();navigate.setText(hasLocation?R.string.map_navigate:R.string.map_set_location);favorite.setImageResource(p.favorite?android.R.drawable.btn_star_big_on:android.R.drawable.btn_star_big_off);favorite.setColorFilter(p.favorite?0xff00bfff:0xffb7c9e6);favorite.setContentDescription(getContext().getString(p.favorite?R.string.map_unfavorite:R.string.map_favorite));
        distance.setText(!hasLocation?getContext().getString(R.string.map_no_location):FiskentraLocationManager.isFresh(fix)?getContext().getString(R.string.map_straight_distance,MapUi.distance(FieldNavigation.distance(fix.getLatitude(),fix.getLongitude(),p.latitude,p.longitude),imperial)):getContext().getString(R.string.map_no_distance));status.setText(syncStatus);
        String path=c==null?"":c.localPhotoPath;if(!path.equals(photoPath)){photoPath=path;loadPhoto(path);}setExpanded(expanded);
    }
    @Override public void setExpanded(boolean value){expanded=value;photo.setVisibility(value&&bitmap!=null?VISIBLE:GONE);photoError.setVisibility(value&&photoFailed?VISIBLE:GONE);details.setVisibility(details.length()>0?VISIBLE:GONE);boolean hasMetrics=details.length()>0||weight.length()>0||length.length()>0;metrics.setVisibility(hasMetrics?VISIBLE:GONE);media.setVisibility(value&&(bitmap!=null||hasMetrics)?VISIBLE:GONE);notes.setVisibility(value&&notes.length()>0?VISIBLE:GONE);}
    private void loadPhoto(String path){int request=++generation;photoFailed=false;photo.setImageDrawable(null);photo.setVisibility(GONE);photoError.setVisibility(GONE);if(bitmap!=null){bitmap.recycle();bitmap=null;}if(path.isEmpty())return;images.execute(()->{Bitmap decoded=null;try{java.io.File file=new java.io.File(path).getCanonicalFile();String root=getContext().getFilesDir().getCanonicalPath()+java.io.File.separator;if(!file.getPath().startsWith(root))throw new java.io.IOException("Photo outside local store");BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),o);o.inSampleSize=1;while(o.outWidth/o.inSampleSize>1024||o.outHeight/o.inSampleSize>1024)o.inSampleSize*=2;o.inJustDecodeBounds=false;decoded=BitmapFactory.decodeFile(file.getPath(),o);}catch(Exception ignored){}final Bitmap result=decoded;main.post(()->{if(closed||request!=generation){if(result!=null)result.recycle();return;}bitmap=result;photoFailed=result==null;photo.setImageBitmap(result);setExpanded(expanded);});});}
    @Override protected void onDetachedFromWindow(){closed=true;++generation;images.shutdownNow();main.removeCallbacksAndMessages(null);photo.setImageDrawable(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}super.onDetachedFromWindow();}
}
