package com.jacks.stocks;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Offline platform drawing/accessibility tests. Requires a real Android runtime. */
@SuppressWarnings("deprecation")
public final class ChartViewTest extends InstrumentationTestCase {
    private Context context() { return getInstrumentation().getTargetContext(); }
    private static BigDecimal b(String value) { return new BigDecimal(value); }
    private int draw(ChartView chart, int widthDp) {
        int width=Math.round(widthDp*chart.getResources().getDisplayMetrics().density);
        chart.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        assertEquals(width,chart.getMeasuredWidth());assertTrue(chart.getMeasuredHeight()>0);
        chart.layout(0,0,width,chart.getMeasuredHeight());
        Bitmap bitmap=Bitmap.createBitmap(width,chart.getMeasuredHeight(),Bitmap.Config.ARGB_8888);
        try {
            chart.draw(new Canvas(bitmap));boolean drawn=false;
            for(int y=0;y<bitmap.getHeight() && !drawn;y+=3)
                for(int x=0;x<width && !drawn;x+=3)drawn=(bitmap.getPixel(x,y)>>>24)!=0;
            assertTrue("Chart/title should draw visible pixels",drawn);
        }finally{bitmap.recycle();}
        return chart.getMeasuredHeight();
    }
    public void testMissingLineAndNavigation() throws Throwable {
        runTestOnUiThread(()->{
            List<String> labels=Arrays.asList("2024-01-01","2024-01-02","2024-01-03","2024-01-04");
            List<BigDecimal> values=new ArrayList<>(Arrays.asList(b("100"),null,b("-20"),null));
            ChartView chart=new ChartView(context(),"Cumulative earnings / loss",labels,values,ChartView.Mode.LINE,false);
            draw(chart,240);String description=chart.getContentDescription().toString();
            assertTrue(description.contains("Latest 2024-01-04: unavailable"));
            assertTrue(description.contains("Latest known 2024-01-03"));
            assertTrue(description.contains("2 missing values, never treated as zero"));
            assertTrue(description.contains("Minimum"));assertTrue(description.contains("Maximum"));
            assertTrue(chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null));
            assertTrue(chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null));
            assertTrue(chart.getContentDescription().toString().contains("Selected: 2024-01-02. Unavailable"));
            assertTrue(chart.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT)));
            values.set(0,b("999"));assertFalse(chart.getContentDescription().toString().contains("999"));
        });
    }
    public void testEmptySingleSignedAndExtremeData() throws Throwable {
        runTestOnUiThread(()->{
            List<List<BigDecimal>> cases=Arrays.asList(Collections.emptyList(),Collections.singletonList(null),
                Collections.singletonList(b("12.34")),Arrays.asList(BigDecimal.ZERO,BigDecimal.ZERO),
                Arrays.asList(b("-1E1000"),null,b("1E1000")),Arrays.asList(b("0.00000001"),b("0.00000002")));
            for(ChartView.Mode mode:ChartView.Mode.values())for(List<BigDecimal> values:cases){
                List<String> labels=new ArrayList<>();for(int i=0;i<values.size();i++)labels.add("Day "+i);
                draw(new ChartView(context(),"P&L",labels,values,mode,true),240);
            }
        });
    }
    public void testDonutAllocationAndPartialValues() throws Throwable {
        runTestOnUiThread(()->{
            List<String> labels=new ArrayList<>();List<BigDecimal> values=new ArrayList<>();
            for(int i=1;i<=10;i++){labels.add("SYMBOL"+i);values.add(BigDecimal.valueOf(i));}
            ChartView chart=new ChartView(context(),"Allocation",labels,values,ChartView.Mode.DONUT,false);
            draw(chart,240);String description=chart.getContentDescription().toString();
            assertTrue(description.contains("Others (3 holdings)"));assertTrue(description.contains("55.00"));
            for(BigDecimal invalid:Arrays.asList(null,b("-1"))){
                ChartView partial=new ChartView(context(),"Allocation",Arrays.asList("A","B"),Arrays.asList(b("100"),invalid),ChartView.Mode.DONUT,true);
                assertTrue(partial.getContentDescription().toString().contains("Allocation weights unavailable"));
                draw(partial,240);
            }
        });
    }
    public void testLargeFontsGrowCharts() throws Throwable {
        runTestOnUiThread(()->{
            Configuration normal=new Configuration(context().getResources().getConfiguration());normal.fontScale=1f;
            Configuration large=new Configuration(normal);large.fontScale=2f;
            List<String> labels=Arrays.asList("LONG_SYMBOL_A","LONG_SYMBOL_B","Missing date");
            List<BigDecimal> values=Arrays.asList(b("123456789.12"),b("234567890.34"),null);
            for(ChartView.Mode mode:ChartView.Mode.values()){
                int a=draw(new ChartView(context().createConfigurationContext(normal),"Cumulative earnings over the selected period",labels,values,mode,false),240);
                int b=draw(new ChartView(context().createConfigurationContext(large),"Cumulative earnings over the selected period",labels,values,mode,true),240);
                assertTrue("Large text requires more vertical space",b>a);
            }
        });
    }
}