package com.jacks.stocks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.jacks.stocks.core.Portfolio;
import com.jacks.stocks.core.Portfolio.*;
import com.jacks.stocks.core.Portfolio.Period;
import com.jacks.stocks.data.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Native, local-first portfolio UI. Network calls exist only behind explicit buttons. */
public final class MainActivity extends Activity {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int EXPORT_CSV=201, IMPORT_CSV=202, BACKUP=203, RESTORE=204;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private StocksApp app;
    private SharedPreferences preferences;
    private LinearLayout root, content;
    private TextView status;
    private Button refresh;
    private int tab, range=1, grouping, activityPage;
    private boolean light, loading, editorShown;
    private int background, surface, foreground, muted, green, red;
    private long seenRevision=-1;
    private volatile long chartGeneration;
    private List<Tx> transactions = new ArrayList<>();
    private List<Quote> quotes = new ArrayList<>();
    private List<Candle> candles = new ArrayList<>();
    private List<Store.Instrument> instruments = new ArrayList<>();
    private List<String> providerWarnings = new ArrayList<>();
    private Map<String,LocalDate> providerSplits = new HashMap<>();
    private Report report;
    private Period dayResult, weekResult, monthResult;
    private LocalDate loadedDate;
    private long lastRefresh;
    private Bundle editorState;
    private String selectedKey;
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
    private final Runnable observe = new Runnable() {
        @Override public void run() {
            if (status != null) status.setText(app.status);
            if (refresh != null) refresh.setEnabled(!app.busy && !loading);
            if (!app.busy && !loading && (seenRevision != app.revision || !today().equals(loadedDate))) load();
            handler.postDelayed(this, 600);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app=(StocksApp)getApplication();
        preferences=getSharedPreferences("appearance", MODE_PRIVATE);
        // Release builds always protect screenshots. Debug capture is explicit and opt-in.
        if(!(BuildConfig.DEBUG && preferences.getBoolean("review_screenshots",false)))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        currency.setMaximumFractionDigits(2);
        if(saved!=null) {
            tab=saved.getInt("tab"); range=saved.getInt("range",1);
            grouping=saved.getInt("grouping"); selectedKey=saved.getString("selected");
            activityPage=saved.getInt("activityPage");
            editorState=saved.getBundle("editor");
        }
        colors(); shell();
    }

    @Override protected void onResume() { super.onResume(); handler.post(observe); }
    @Override protected void onPause() { handler.removeCallbacks(observe); super.onPause(); }
    @Override protected void onDestroy() { chartGeneration++;super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab",tab); state.putInt("range",range); state.putInt("grouping",grouping);
        state.putString("selected",selectedKey); state.putBundle("editor",editorState);
        state.putInt("activityPage",activityPage);
    }
    private LocalDate today() { return LocalDate.now(IST); }
    private int dp(float v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private void colors() {
        String mode=preferences.getString("theme","System");
        light=mode.equals("Light") || (mode.equals("System") &&
            (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES);
        background=Color.parseColor(light?"#F1F5FA":"#0B1422");
        surface=Color.parseColor(light?"#FFFFFF":"#152338");
        foreground=Color.parseColor(light?"#12233C":"#EEF4FF");
        muted=Color.parseColor(light?"#51627A":"#AAB8CC");
        green=Color.parseColor(light?"#006F50":"#40DDAC");
        red=Color.parseColor(light?"#B3233A":"#FF8B96");
    }
    private GradientDrawable rounded(int color) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(16)); return d;
    }
    private LinearLayout vertical() {
        LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v;
    }
    private TextView text(String value,int size,int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0,dp(4),0,dp(4)); return t;
    }
    private TextView label(LinearLayout parent,String value,int size,int color) {
        TextView t=text(value,size,color); parent.addView(t); return t;
    }
    private Button button(LinearLayout parent,String caption,Runnable action) {
        Button b=new Button(this); b.setText(caption); b.setAllCaps(false); b.setTextColor(foreground);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(light?"#E3EBF5":"#20334D")));
        b.setMinHeight(dp(48)); b.setOnClickListener(v -> action.run()); parent.addView(b); return b;
    }
    private LinearLayout card(String title) {
        LinearLayout box=vertical(); box.setPadding(dp(16),dp(12),dp(16),dp(14));
        box.setBackground(rounded(surface));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(8),0,dp(4));
        content.addView(box,p);
        if(!title.isEmpty()) { TextView h=label(box,title,16,foreground); h.setTypeface(null,Typeface.BOLD); }
        return box;
    }
    private void shell() {
        root=vertical(); root.setBackgroundColor(background);
        root.setPadding(dp(16),dp(8),dp(16),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(dp(16)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),
                dp(16)+insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this); logo.setImageResource(R.drawable.ic_brand);
        logo.setBackground(rounded(Color.parseColor("#10243A"))); logo.setContentDescription("JK stock-market logo");
        header.addView(logo,new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout name=vertical(); name.setPadding(dp(12),0,0,0);
        label(name,"Jacks stocks",25,foreground).setTypeface(null,Typeface.BOLD);
        label(name,"PERSONAL PORTFOLIO  /  NSE",11,muted);
        header.addView(name,new LinearLayout.LayoutParams(0,-2,1)); root.addView(header);
        if(BuildConfig.DEBUG && preferences.getBoolean("review_demo",false))
            label(root,"DEMO · fictional portfolio, not your holdings",11,green);
        refresh=button(root,"Refresh prices",this::refreshPrices);
        refresh.setTextColor(light?Color.WHITE:Color.parseColor("#052A20"));
        refresh.setBackground(rounded(light?green:Color.parseColor("#40DDAC")));
        refresh.setBackgroundTintList(null);
        status=label(root,app.status,12,muted);
        status.setMaxLines(3);status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setContentDescription("Refresh status. Tap for full details.");
        status.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Status").setMessage(app.status).setPositiveButton("OK",null).show());
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        content=vertical(); content.setPadding(0,0,0,dp(16)); scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout row=new LinearLayout(this);
        String[] titles={"Overview","Holdings","Activity","Charts","Settings"};
        for(int i=0;i<titles.length;i++) {
            final int n=i; Button b=button(row,titles[i],()->{tab=n; selectedKey=null; shell(); render();});
            b.setTextColor(i==tab?green:muted);b.setTextSize(12);b.setLetterSpacing(0);
            b.setMinimumWidth(0);b.setMinWidth(0);b.setPadding(dp(2),dp(8),dp(2),dp(8));
            b.setLayoutParams(new LinearLayout.LayoutParams(0,dp(56),1));
        }
        root.addView(row); setContentView(root); root.requestApplyInsets();
    }
    private void load() {
        loading=true; long generation=app.revision;
        app.worker.execute(()->{
            try {
                app.store.useYahooMarketData();
                List<Tx> tx=app.store.transactions(); List<Quote> q=app.store.quotes();
                List<Candle> c=app.store.candles(); List<Store.Instrument> ins=app.store.instruments();
                Set<String> activeKeys=new HashSet<>();for(Tx t:tx)activeKeys.add(t.key);
                q.removeIf(quote->!activeKeys.contains(quote.key));c.removeIf(candle->!activeKeys.contains(candle.key));
                Map<String,LocalDate> splits=app.store.marketSplits();
                List<String> notes=new ArrayList<>();
                for(Map.Entry<String,String> entry:app.store.marketNotes().entrySet())
                    if(activeKeys.contains(entry.getKey()))notes.addAll(Arrays.asList(entry.getValue().split("\n")));
                for(Map.Entry<String,LocalDate> split:splits.entrySet())if(activeKeys.contains(split.getKey())){
                    boolean ownedBefore=false,recorded=false;String symbol=split.getKey();
                    for(Tx t:tx)if(t.key.equals(split.getKey())){
                        symbol=t.symbol;
                        if(t.date.isBefore(split.getValue()) && (t.type==Type.BUY || t.type==Type.OPENING))ownedBefore=true;
                        if(t.type==Type.SPLIT && t.date.equals(split.getValue()))recorded=true;
                    }
                    if(ownedBefore && !recorded){
                        final String key=split.getKey();q.removeIf(v->v.key.equals(key));c.removeIf(v->v.key.equals(key));
                        notes.add(symbol+": Yahoo reports a split/bonus on "+split.getValue()+". Reconcile and record it before valuation; shares are never changed automatically.");
                    }
                }
                LocalDate valuationDate=today();
                long stamp=app.store.lastRefresh(); Report result=Portfolio.report(tx,q,c,valuationDate,true);
                Period day=Portfolio.period(tx,q,c,valuationDate.minusDays(1),valuationDate,true);
                Period week=Portfolio.period(tx,q,c,valuationDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(1),valuationDate,true);
                Period month=Portfolio.period(tx,q,c,valuationDate.withDayOfMonth(1).minusDays(1),valuationDate,true);
                runOnUiThread(()->{
                    if(isFinishing() || isDestroyed()) return;
                    transactions=tx; quotes=q; candles=c; instruments=ins; report=result; lastRefresh=stamp;
                    providerWarnings=notes;providerSplits=splits;
                    dayResult=day;weekResult=week;monthResult=month;loadedDate=valuationDate;
                    seenRevision=generation; loading=false; render();
                    if(editorState!=null && editorState.getString("draft","").equals(app.lastSavedDraft))editorState=null;
                    if(editorState!=null && !editorShown) showEditor(null,null,editorState);
                });
            } catch(Exception e) { runOnUiThread(()->{
                if(isFinishing() || isDestroyed())return;
                loading=false;seenRevision=generation;loadedDate=today();
                app.status="Portfolio could not be loaded: "+safe(e);render();error(e);
            }); }
        });
    }
    private void render() {
        chartGeneration++;
        content.removeAllViews();
        if(tab==4){settings();return;}
        if(report==null) {
            label(content,loading?"Loading your portfolio…":"Portfolio unavailable. Open Settings for data recovery or retry.",18,muted);
            if(!loading)button(content,"Retry loading portfolio",this::load);
            return;
        }
        if(selectedKey!=null) {details(selectedKey); return;}
        switch(tab) {
            case 0: overview(); break; case 1: holdings(); break; case 2: activity(); break;
            case 3: charts(); break; default: settings();
        }
    }
    private String money(BigDecimal value) { return value==null?"Unavailable":currency.format(value); }
    private String signed(BigDecimal value) { return value==null?"Unavailable":(value.signum()>0?"+":"")+money(value); }
    private String number(BigDecimal value) {return value.stripTrailingZeros().toPlainString();}
    private int gainColor(BigDecimal v) { return v==null?muted:v.signum()<0?red:green; }
    private String time(long ms) { return ms<=0?"Not refreshed":Instant.ofEpochMilli(ms).atZone(IST).format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'IST'")); }
    private void metric(LinearLayout box,String name,BigDecimal value,boolean gain) {
        label(box,name,13,muted); label(box,gain?signed(value):money(value),25,gain?gainColor(value):foreground);
    }
    private void overview() {
        if(transactions.isEmpty()) {
            LinearLayout empty=card("Your portfolio starts here");
            label(empty,"Record a purchase, or add an opening holding with its average cost. Your data stays on this phone.",16,foreground);
            button(empty,"Add first transaction",()->showEditor(null,null,null));
            button(empty,"Yahoo Finance · no login needed",()->{tab=4;render();});
            label(empty,"Already have a ledger? Import the Jacks stocks CSV format in Settings. No sample stocks or prices are mixed into your portfolio.",14,muted);
            return;
        }
        LinearLayout summary=card("PORTFOLIO VALUE");
        label(summary,money(report.value),34,foreground).setTypeface(null,Typeface.BOLD);
        label(summary,"Last refresh: "+time(lastRefresh),12,muted);
        label(summary,"Yahoo Finance · delayed / cached prices",12,muted);
        metric(summary,"Remaining cost basis",report.cost,false);
        metric(summary,"Unrealized profit / loss",report.unrealized,true);
        LinearLayout performance=card("Your earnings");
        periodMetric(performance,"Today · calendar day",dayResult);
        periodMetric(performance,"This week · Monday to date",weekResult);
        periodMetric(performance,"This month",monthResult);
        label(performance,"Includes recorded sales, charges and dividends. Purchases are not earnings. Figures are in INR, not cash-flow-adjusted percentage returns.",12,muted);
        LinearLayout lifetime=card("Since tracking began");
        metric(lifetime,"Realized P&L · FIFO, net of recorded fees",report.realized,true);
        metric(lifetime,"Dividends, net of recorded fees",report.dividends,true);
        metric(lifetime,"Total gain · realized + unrealized + dividends",report.totalGain,true);
        warningCard();
        button(content,"+ Record transaction",()->showEditor(null,null,null));
    }
    private void periodMetric(LinearLayout box,String title,Period p) {
        if(p==null)return;
        metric(box,title,p.amount,true);
        if(p.reason!=null && !p.reason.isEmpty()) {
            TextView note=label(box,p.amount==null?"Needs price history or a complete starting balance. Tap for details.":"Uses cached / carried prices. Tap for calculation details.",12,muted);
            note.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(title).setMessage(p.reason).setPositiveButton("OK",null).show());
        }
    }
    private void warningCard() {
        LinkedHashSet<String> warnings=new LinkedHashSet<>(report.warnings);
        warnings.addAll(providerWarnings);
        if(!warnings.isEmpty()) {
            LinearLayout box=card("Data quality");
            label(box,warnings.size()+" note(s) about dates, missing data or corporate actions.",13,muted);
            button(box,"View data-quality notes",()->new AlertDialog.Builder(this).setTitle("Data quality")
                .setMessage(String.join("\n\n",warnings)).setPositiveButton("OK",null).show());
        }
    }
    private void holdings() {
        button(content,"+ Add holding / transaction",()->showEditor(null,null,null));
        boolean any=false;
        for(Position p:report.positions) if(p.quantity.signum()>0) {
            any=true; LinearLayout box=card(p.symbol+"  ·  "+number(p.quantity)+" shares");
            label(box,p.name,14,muted); metric(box,"Market value",p.value,false);
            label(box,"Price "+money(p.price)+"  |  Cost "+money(p.cost),14,foreground);
            label(box,"Unrealized "+signed(p.unrealized),17,gainColor(p.unrealized));
            Quote q=findQuote(p.key); label(box,q==null?"No quote yet · tap Refresh prices":"Cached quote: "+time(q.asOfMillis),12,muted);
            label(box,"Valuation may use a newer available close; check Data quality below.",12,muted);
            button(box,"View "+p.symbol,()->{selectedKey=p.key;render();});
        }
        if(!any) label(card("No open holdings"),"Record a purchase or opening holding to start tracking.",16,muted);
        warningCard();
    }
    private Quote findQuote(String key) {for(Quote q:quotes) if(q.key.equals(key))return q;return null;}
    private void activity() {
        button(content,"+ Record transaction",()->showEditor(null,null,null));
        label(content,"Dated ledger · newest first. Tap an entry to edit or delete. Same-day entries follow their saved order.",13,muted);
        List<Tx> reversed=new ArrayList<>(transactions); Collections.reverse(reversed);
        int pages=Math.max(1,(reversed.size()+99)/100);activityPage=Math.min(activityPage,pages-1);
        if(pages>1){
            label(content,"Page "+(activityPage+1)+" of "+pages+" · 100 entries per page",13,muted);
            if(activityPage>0)button(content,"Newer entries",()->{activityPage--;render();});
            if(activityPage+1<pages)button(content,"Older entries",()->{activityPage++;render();});
        }
        for(Tx tx:reversed.subList(activityPage*100,Math.min(reversed.size(),(activityPage+1)*100))) {
            LinearLayout box=card(tx.symbol+"  ·  "+tx.type);
            label(box,tx.date+"  |  "+(tx.type==Type.DIVIDEND?"Received "+money(tx.price):
                tx.type==Type.SPLIT?"Quantity multiplier ×"+number(tx.quantity):number(tx.quantity)+" × "+money(tx.price)),15,foreground);
            if(tx.fees.signum()>0)label(box,"Charges "+money(tx.fees),13,muted);
            button(box,"Edit entry #"+tx.id,()->showEditor(tx,null,null));
        }
        if(transactions.isEmpty())label(content,"No transactions yet.",18,muted);
    }
    private void details(String key) {
        Position p=null; for(Position candidate:report.positions) if(candidate.key.equals(key))p=candidate;
        button(content,"← Back",()->{selectedKey=null;render();});
        if(p==null)return;
        final Position position=p;
        LinearLayout box=card(p.symbol+" · "+p.name);
        label(box,p.key,12,muted); metric(box,"Latest available valuation price",p.price,false);
        metric(box,"Holding value",p.value,false); metric(box,"Cost basis",p.cost,false);
        metric(box,"Unrealized P&L",p.unrealized,true); metric(box,"Realized P&L",p.realized,true);
        Quote q=findQuote(key); if(q!=null) {
            label(box,"Quote time: "+time(q.asOfMillis)+"\nFetched: "+time(q.fetchedMillis),12,muted);
            label(box,"Previous available daily close: "+money(q.previousClose),13,muted);
        }
        List<String> labels=new ArrayList<>(); List<BigDecimal> prices=new ArrayList<>();
        LocalDate latestSplit=providerSplits.get(key);
        for(Tx tx:transactions)if(tx.key.equals(key) && tx.type==Type.SPLIT && (latestSplit==null || tx.date.isAfter(latestSplit)))latestSplit=tx.date;
        for(Candle c:candles) if(c.key.equals(key) && !c.date.isBefore(today().minusMonths(3))) {
            labels.add(c.date.toString()); prices.add(c.close!=null && c.close.signum()>0 && (latestSplit==null || !c.date.isBefore(latestSplit))?c.close:null);
        }
        addChart("Price history · last 3 months",labels,prices,ChartView.Mode.LINE);
        label(content,"Historical provider prices; corporate-action adjustment conventions may differ. Transaction markers are listed below.",12,muted);
        warningCard();
        button(content,"Record "+p.symbol+" transaction",()->showEditor(null,new Store.Instrument(position.key,position.symbol,position.name),null));
        for(Tx tx:transactions)if(tx.key.equals(key)) label(content,tx.date+"  "+tx.type+"  "+number(tx.quantity)+" @ "+money(tx.price),14,foreground);
    }

    private LocalDate rangeStart() {
        LocalDate earliest=today(); for(Tx tx:transactions) if(tx.date.isBefore(earliest))earliest=tx.date;
        LocalDate start;
        switch(range) {
            case 0:start=today().minusWeeks(1);break; case 1:start=today().minusMonths(1);break;
            case 2:start=today().minusMonths(3);break; case 3:start=today().minusMonths(6);break;
            case 4:start=today().minusYears(1);break; default:start=earliest;
        }
        return start.isBefore(earliest)?earliest:start;
    }
    private void addChart(String title,List<String> labels,List<BigDecimal> values,ChartView.Mode mode) {
        LinearLayout box=card("");
        box.addView(new ChartView(this,title,labels,values,mode,light),new LinearLayout.LayoutParams(-1,-2));
    }
    private void charts() {
        if(transactions.isEmpty()) {label(card("Charts"),"Add your transactions to create portfolio charts.",17,muted);return;}
        Spinner ranges=spinner(content,new String[]{"1 week","1 month","3 months","6 months","1 year","All history"},range);
        ranges.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(AdapterView<?> p) {}
            @Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(range!=pos){range=pos;render();}}
        });
        Spinner groups=spinner(content,new String[]{"Daily P&L bars","Weekly P&L bars","Monthly P&L bars"},grouping);
        groups.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(AdapterView<?> p) {}
            @Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(grouping!=pos){grouping=pos;render();}}
        });
        label(content,"Closing-price history; latest point uses cached quotes. Gaps mean unavailable data, not zero. Value changes include transactions; P&L excludes capital invested.",13,muted);
        TextView pending=label(content,"Calculating charts…",15,muted);
        int requestedRange=range, requestedGrouping=grouping; long revision=seenRevision, requestGeneration=chartGeneration;
        List<Tx> tx=new ArrayList<>(transactions); List<Quote> q=new ArrayList<>(quotes); List<Candle> c=new ArrayList<>(candles);
        LocalDate start=rangeStart(), end=today();
        app.chartWorker.execute(()->{
            if(requestGeneration!=chartGeneration) {
                return;
            }
            try {
                // Build one indexed history, rather than reparsing every candle for each bar.
                List<Point> points=Portfolio.history(tx,q,c,start.minusDays(1),end);
                if(requestGeneration!=chartGeneration)return;
                Map<LocalDate,Point> byDate=new HashMap<>();
                List<String> dates=new ArrayList<>(), barDates=new ArrayList<>();
                List<BigDecimal> values=new ArrayList<>(), gains=new ArrayList<>(), bars=new ArrayList<>();
                LocalDate barrier=null;
                for(Map.Entry<String,LocalDate> split:providerSplits.entrySet()){
                    boolean inLedger=false;for(Tx t:tx)if(t.key.equals(split.getKey()))inLedger=true;
                    if(inLedger && (barrier==null || split.getValue().isAfter(barrier)))barrier=split.getValue();
                }
                for(Point p:points){
                    Point safe=barrier!=null && p.date.isBefore(barrier)?new Point(p.date,null,null):p;
                    byDate.put(safe.date,safe);
                    if(!safe.date.isBefore(start)){dates.add(safe.date.toString());values.add(safe.value);gains.add(safe.gain);}
                }
                LocalDate cursor=start;
                while(!cursor.isAfter(end)) {
                    LocalDate finish=requestedGrouping==0?cursor:requestedGrouping==1?
                        cursor.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)):cursor.with(TemporalAdjusters.lastDayOfMonth());
                    if(finish.isAfter(end))finish=end;
                    Point before=byDate.get(cursor.minusDays(1)), after=byDate.get(finish);
                    BigDecimal amount=before==null || after==null || before.gain==null || after.gain==null?null:after.gain.subtract(before.gain);
                    // Current bar must share Overview's missing-session baseline guard.
                    if(amount!=null && finish.equals(end))amount=Portfolio.period(tx,q,c,cursor.minusDays(1),finish,true).amount;
                    barDates.add(cursor+(cursor.equals(finish)?"":" to "+finish)); bars.add(amount);
                    cursor=finish.plusDays(1);
                }
                runOnUiThread(()->{
                    if(isDestroyed() || requestGeneration!=chartGeneration || tab!=3 || selectedKey!=null || range!=requestedRange || grouping!=requestedGrouping || seenRevision!=revision || pending.getParent()!=content)return;
                    content.removeView(pending);
                    addChart("Portfolio value",dates,values,ChartView.Mode.LINE);
                    addChart("Cumulative earnings / loss",dates,gains,ChartView.Mode.LINE);
                    addChart(new String[]{"Daily","Weekly","Monthly"}[grouping]+" earnings / loss",barDates,bars,ChartView.Mode.BAR);
                    List<String> names=new ArrayList<>(); List<BigDecimal> allocation=new ArrayList<>(), contribution=new ArrayList<>();
                    boolean complete=true;
                    for(Position p:report.positions)if(p.quantity.signum()>0){
                        names.add(p.symbol); allocation.add(p.value); contribution.add(p.unrealized);
                        if(p.value==null)complete=false;
                    }
                    if(complete)addChart("Allocation by current market value",names,allocation,ChartView.Mode.DONUT);
                    else label(card("Allocation unavailable"),"Refresh missing prices before calculating portfolio weights.",14,muted);
                    addChart("Unrealized P&L by current holding",names,contribution,ChartView.Mode.BAR);
                    warningCard();
                });
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())pending.setText(getString(R.string.charts_unavailable,safe(e)));});}
        });
    }

    private Spinner spinner(LinearLayout parent,String[] values,int selected) {
        Spinner s=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,values){
            @Override public View getView(int pos,View convert,android.view.ViewGroup group){
                TextView v=(TextView)super.getView(pos,convert,group);v.setTextColor(foreground);v.setTextSize(16);return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);s.setSelection(selected);s.setMinimumHeight(dp(48));parent.addView(s);return s;
    }
    private EditText field(LinearLayout parent,String caption,String value,boolean decimal) {
        label(parent,caption,13,muted);
        EditText edit=new EditText(this);edit.setTextColor(foreground);edit.setHintTextColor(muted);
        edit.setSingleLine(true);edit.setText(value);edit.setTextSize(16);edit.setMinHeight(dp(48));
        edit.setInputType(decimal?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT);
        parent.addView(edit);return edit;
    }
    private void showEditor(Tx original,Store.Instrument preset,Bundle restore) {
        if(editorShown)return;
        if(app.busy || loading){toast("Please wait for the current operation.");return;}
        editorShown=true;
        LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(12));box.setBackgroundColor(surface);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        String key=original!=null?original.key:preset!=null?preset.key:"";
        String symbol=original!=null?original.symbol:preset!=null?preset.symbol:"";
        String name=original!=null?original.name:preset!=null?preset.name:"";
        Bundle state=restore!=null?new Bundle(restore):new Bundle();
        if(!state.containsKey("draft"))state.putString("draft",UUID.randomUUID().toString());
        long id=restore!=null?restore.getLong("id"):original==null?0:original.id;
        state.putLong("id",id);editorState=state;
        label(box,"Search the downloaded stock list, choose an existing holding, or enter its NSE ISIN manually. Download the list in Settings.",13,muted);
        AutoCompleteTextView search=new AutoCompleteTextView(this);search.setTextColor(foreground);search.setHintTextColor(muted);
        search.setHint("Search symbol or company");search.setThreshold(1);search.setMinHeight(dp(48));box.addView(search);
        LinkedHashMap<String,Store.Instrument> choices=new LinkedHashMap<>();
        for(Store.Instrument i:instruments)choices.put(i.symbol+" · "+i.name,i);
        for(Tx tx:transactions)choices.put(tx.symbol+" · "+tx.name,new Store.Instrument(tx.key,tx.symbol,tx.name));
        search.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_dropdown_item_1line,new ArrayList<>(choices.keySet())));
        EditText symbolField=field(box,"Trading symbol",state.getString("symbol",symbol),false);
        EditText nameField=field(box,"Company name",state.getString("name",name),false);
        EditText keyField=field(box,"Instrument key (NSE_EQ|ISIN)",state.getString("key",key),false);
        search.setOnItemClickListener((p,v,pos,row)->{
            Store.Instrument i=choices.get(search.getText().toString());
            if(i!=null){symbolField.setText(i.symbol);nameField.setText(i.name);keyField.setText(i.key);}
        });
        String[] types={"BUY","SELL","DIVIDEND","SPLIT","OPENING"};
        Type current=original==null?Type.BUY:original.type;
        int selected=Arrays.asList(types).indexOf(state.getString("type",current.name()));
        label(box,"Transaction type",13,muted); Spinner type=spinner(box,types,Math.max(0,selected));
        label(box,"BUY / SELL: quantity and price per share. DIVIDEND: quantity 0, price = total received. SPLIT / BONUS: quantity = new total ÷ old total; price and fees 0. OPENING: existing quantity and average cost; earlier performance is unknown.",12,muted);
        EditText date=field(box,"Date (YYYY-MM-DD, India time)",state.getString("date",original==null?today().toString():original.date.toString()),false);
        EditText quantity=field(box,"Quantity / split multiplier",state.getString("quantity",original==null?"":number(original.quantity)),true);
        EditText price=field(box,"Price in INR / total dividend",state.getString("price",original==null?"":number(original.price)),true);
        EditText fees=field(box,"Charges in INR",state.getString("fees",original==null?"0":number(original.fees)),true);
        String[] keys={"symbol","name","key","date","quantity","price","fees"};
        EditText[] edits={symbolField,nameField,keyField,date,quantity,price,fees};
        for(int n=0;n<edits.length;n++) {
            final String fieldKey=keys[n];EditText edit=edits[n];state.putString(fieldKey,edit.getText().toString());
            edit.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int a,int c,int f){}
                public void onTextChanged(CharSequence s,int a,int b,int c){state.putString(fieldKey,s.toString());}
                public void afterTextChanged(Editable e){}
            });
        }
        state.putString("type",types[type.getSelectedItemPosition()]);
        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int pos,long row){state.putString("type",types[pos]);}
        });
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(id==0?"Record transaction":"Edit transaction #"+id)
            .setView(scroll).setNegativeButton("Cancel",(d,w)->editorState=null).setPositiveButton("Save",null);
        if(id!=0)builder.setNeutralButton("Delete",null);
        AlertDialog dialog=builder.create();
        dialog.setOnCancelListener(d->editorState=null);
        dialog.setOnDismissListener(d->editorShown=false);
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(app.busy){toast("Please wait for the current operation.");return;}
                try {
                    String instrument=keyField.getText().toString().trim().toUpperCase(Locale.ROOT);
                    if(!instrument.startsWith("NSE_EQ|"))instrument="NSE_EQ|"+instrument;
                    LocalDate when=LocalDate.parse(date.getText().toString().trim());
                    if(when.isAfter(today()) || when.isBefore(LocalDate.of(2000,1,1)))throw new IllegalArgumentException("Date must be between 2000-01-01 and today.");
                    Tx tx=new Tx(id,when,instrument,symbolField.getText().toString().trim().toUpperCase(Locale.ROOT),
                        nameField.getText().toString().trim(),Type.valueOf(types[type.getSelectedItemPosition()]),
                        decimal(quantity),decimal(price),decimal(fees));
                    saveEditor(dialog,tx,state.getString("draft"));
                }catch(Exception e){error(e);}
            });
            if(id!=0)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->
                new AlertDialog.Builder(this).setTitle("Delete this transaction?")
                    .setMessage("The complete ledger will be recalculated. Deletion is rejected if it would make a later sale invalid.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Delete",(a,b)->{
                        if(app.busy){toast("Please wait for the current operation.");return;}
                        editorState=null;dialog.dismiss();job("Deleting transaction…",()->{app.store.delete(id);return "Transaction deleted.";});
                    }).show());
        });
        dialog.show();
    }
    private void saveEditor(AlertDialog dialog,Tx tx,String draftId) {
        if(app.busy)return;
        app.busy=true;app.status="Validating and saving transaction…";
        dialog.setCancelable(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        if(dialog.getButton(AlertDialog.BUTTON_NEUTRAL)!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        app.worker.execute(()->{
            Exception failure=null;
            try {
                // Database validates and replays the complete proposed ledger off the main thread.
                app.store.save(tx);app.lastSavedDraft=draftId;
                app.status="Transaction saved. Tap Refresh prices to update valuations.";
            }catch(Exception e){failure=e;app.status="Transaction not saved: "+safe(e);}
            finally{app.revision++;app.busy=false;}
            final Exception error=failure;
            runOnUiThread(()->{
                if(isFinishing() || isDestroyed())return;
                if(error==null){editorState=null;dialog.dismiss();}
                else {
                    dialog.setCancelable(true);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    if(dialog.getButton(AlertDialog.BUTTON_NEUTRAL)!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                    error(error);
                }
            });
        });
    }
    private BigDecimal decimal(EditText e) {
        String s=e.getText().toString().trim();return s.isEmpty()?ZERO:new BigDecimal(s);
    }

    private void settings() {
        LinearLayout provider=card("Price source · Yahoo Finance");
        label(provider,"No account. No API key. Tap Refresh prices to update NSE quotes and daily history using Yahoo Finance (.NS symbols).",16,foreground);
        label(provider,"Prices may be delayed. This unofficial endpoint has no availability guarantee and may be blocked or rate-limited. The app stops on access errors; it does not bypass them. Review Yahoo's applicable terms before use.",13,muted);
        label(provider,"No automatic updates, background sync or trading. Quote timestamps show the market-data time, not just when you tapped Refresh.",13,muted);
        button(provider,"Yahoo Finance terms",()->openLink("https://legal.yahoo.com/us/en/yahoo/terms/otos/index.html"));
        label(provider,instruments.size()+" NSE instruments cached for search.",13,muted);
        button(provider,"Download NSE stock list",()->job("Downloading NSE instrument list…",()->{
            List<Store.Instrument> all=NseCatalogue.download();app.store.saveInstruments(all);
            return "Downloaded "+all.size()+" NSE instruments. Stock search is ready.";
        }));
        LinearLayout appearance=card("Appearance");
        String[] themes={"System","Light","Dark"};
        Spinner theme=spinner(appearance,themes,Math.max(0,Arrays.asList(themes).indexOf(preferences.getString("theme","System"))));
        theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int pos,long id){
                if(!themes[pos].equals(preferences.getString("theme","System"))){
                    preferences.edit().putString("theme",themes[pos]).apply();colors();shell();render();
                }
            }
        });
        label(provider,"Stock names and ISINs: NSE public catalogue. Prices and history: Yahoo Finance. Symbols that Yahoo does not cover are reported as unavailable.",12,muted);
        LinearLayout data=card("Your data");
        label(data,"Transactions and prices are stored privately on this phone. Automatic cloud backup and device-transfer backup are disabled. Uninstalling deletes local data: keep an encrypted ledger backup.",14,foreground);
        button(data,"Export transactions CSV",()->new AlertDialog.Builder(this).setTitle("Unencrypted export")
            .setMessage("CSV includes your transaction history in readable text. Save it only to a trusted location. Import text columns as text in spreadsheets; never evaluate formulas from untrusted CSV.")
            .setNegativeButton("Cancel",null).setPositiveButton("Choose file",(d,w)->pick(EXPORT_CSV,true,"text/csv","jacks-stocks-transactions.csv")).show());
        button(data,"Import transactions CSV",()->pick(IMPORT_CSV,false,"*/*",null));
        button(data,"Create encrypted ledger backup",()->pick(BACKUP,true,"application/octet-stream","jacks-stocks-ledger.jstocks"));
        button(data,"Restore encrypted ledger backup",()->pick(RESTORE,false,"*/*",null));
        label(data,"Backups contain the transaction ledger, not downloaded prices or appearance preferences. Quotes can be downloaded again. CSV imports append entries; importing the same file twice creates duplicates. Use encrypted restore to replace a ledger.",12,muted);
        if(BuildConfig.DEBUG){
            LinearLayout review=card("Debug review");
            Switch screenshots=new Switch(this);screenshots.setText(R.string.allow_review_screenshots);screenshots.setTextColor(foreground);
            screenshots.setChecked(preferences.getBoolean("review_screenshots",false));review.addView(screenshots);
            label(review,"Off by default. Enable only for sample data: screenshots and recent-app previews can expose your portfolio. Release builds always remain protected.",12,muted);
            screenshots.setOnCheckedChangeListener((b,checked)->{
                preferences.edit().putBoolean("review_screenshots",checked).apply();
                if(checked)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            });
        }
        LinearLayout about=card("Jacks stocks "+BuildConfig.VERSION_NAME+"  ·  JK");
        label(about,"Personal NSE-equity tracker. INR values; India dates; FIFO cost basis. Today = calendar day using the last available prior close; week = Monday onward; month = calendar month. Closed-market days normally have zero price movement. P&L includes recorded charges and dividends, not unrecorded taxes or cash balances.",13,muted);
        label(about,"Opening holdings establish a tracking baseline, not reconstructed earlier trades. Historical return charts crossing split/bonus events may be unavailable because provider adjustment conventions are not guaranteed. Charts are not tax reports or investment advice.",13,muted);
        label(about,"Privacy: only Yahoo tickers and date ranges are requested—not portfolio quantities, purchase costs or dividends. No login credentials, analytics or advertising SDKs. Screenshots are protected unless explicitly enabled in a debug build.",13,muted);
    }
    private void openLink(String url) {
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("No browser available.");}
    }
    private void refreshPrices() {
        if(transactions.isEmpty()){toast("Add a holding or transaction first.");return;}
        job("Refreshing prices on request…",()->{
            app.store.useYahooMarketData();
            YahooFinanceClient client=new YahooFinanceClient();
            List<Tx> tx=app.store.transactions();
            LinkedHashMap<String,LocalDate> starts=new LinkedHashMap<>();Map<String,String> symbols=new HashMap<>();
            for(Tx t:tx){LocalDate previous=starts.get(t.key);if(previous==null || t.date.isBefore(previous))starts.put(t.key,t.date);symbols.put(t.key,t.symbol);}
            List<String> failures=new ArrayList<>();int quoteCount=0, historyCount=0;
            Map<String,String> currentSymbols=new HashMap<>();
            for(Store.Instrument i:app.store.instruments())currentSymbols.put(i.key,i.symbol);
            for(Map.Entry<String,LocalDate> entry:starts.entrySet()) {
                String key=entry.getKey(),symbol=currentSymbols.containsKey(key)?currentSymbols.get(key):symbols.get(key);
                app.status="Yahoo Finance · fetching "+symbol+"…";
                LocalDate from=entry.getValue().minusDays(10);
                if(from.isBefore(LocalDate.of(2000,1,1)))from=LocalDate.of(2000,1,1);
                try {
                    YahooFinanceClient.Result result=client.fetch(key,symbol,from,today());
                    List<Candle> completed=new ArrayList<>();
                    for(Candle candle:result.candles)if(candle.date.isBefore(today()))completed.add(candle);
                    app.store.saveYahooMarket(key,result.quote,completed,from,today().minusDays(1),result.latestSplit,result.warnings);
                    if(result.quote!=null)quoteCount++;else failures.add(symbol+": latest quote unavailable");
                    historyCount+=completed.size();
                    if(completed.isEmpty())failures.add(symbol+": historical closes unavailable");
                }catch(YahooFinanceClient.RequestException e){
                    failures.add(symbol+": "+safe(e));
                    if(e.statusCode==401 || e.statusCode==403 || e.statusCode==429)break;
                }catch(Exception e){failures.add(symbol+": "+safe(e));}
            }
            if(quoteCount>0 || historyCount>0)app.store.setLastRefresh(System.currentTimeMillis());
            String result="Yahoo Finance: "+quoteCount+" quotes, "+historyCount+" closing prices saved.";
            if(!failures.isEmpty())result+=" Partial/unavailable: "+String.join("; ",failures.subList(0,Math.min(6,failures.size())))+
                (failures.size()>6?"; more instruments unavailable":"")+". Earlier cached prices are retained.";
            return result;
        });
    }
    private interface Work {String run() throws Exception;}
    private void job(String message,Work work) {
        if(app.busy){toast("Please wait for the current operation.");return;}
        app.busy=true;app.status=message;if(status!=null)status.setText(message);
        app.worker.execute(()->{
            try{app.status=work.run();}catch(Exception e){app.status="Could not complete: "+safe(e);}
            finally{app.revision++;app.busy=false;}
        });
    }
    private String safe(Exception e) {
        // Never display raw HTTP bodies or credential-bearing URLs.
        if(e instanceof java.net.UnknownHostException)return "No network connection or host unavailable";
        if(e instanceof java.net.SocketTimeoutException)return "Connection timed out; retry when online";
        String message=e.getMessage();return message==null?e.getClass().getSimpleName():message;
    }
    private void error(Exception e){if(!isFinishing() && !isDestroyed())new AlertDialog.Builder(this).setTitle("Please check").setMessage(safe(e)).setPositiveButton("OK",null).show();}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void pick(int request,boolean create,String type,String name) {
        if(app.busy){toast("Please wait for the current operation.");return;}
        Intent intent=new Intent(create?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(type);
        if(name!=null)intent.putExtra(Intent.EXTRA_TITLE,name);
        try{startActivityForResult(intent,request);}catch(Exception e){error(e);}
    }
    @Override protected void onActivityResult(int request,int result,Intent intent) {
        super.onActivityResult(request,result,intent);
        if(result!=RESULT_OK || intent==null || intent.getData()==null)return;
        Uri uri=intent.getData();
        if(request==EXPORT_CSV)job("Exporting CSV…",()->{
            try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                if(out==null)throw new java.io.IOException("Could not open destination");Backup.writeCsv(out,app.store.transactions());
            }return "Transactions exported. CSV is unencrypted.";
        });
        else if(request==IMPORT_CSV)new AlertDialog.Builder(this).setTitle("Append imported transactions?")
            .setMessage("Use a Jacks stocks CSV export or the documented format. Existing entries remain. Duplicate imports create duplicate transactions. The whole import is rejected if any entry is invalid.")
            .setNegativeButton("Cancel",null).setPositiveButton("Import",(d,w)->job("Importing CSV…",()->{
                List<Tx> imported;try(InputStream in=getContentResolver().openInputStream(uri)){
                    if(in==null)throw new java.io.IOException("Could not open source");imported=Backup.readCsv(in);
                }app.store.importTransactions(imported,false);return "Imported "+imported.size()+" transactions.";
            })).show();
        else if(request==BACKUP)password(uri,false);
        else if(request==RESTORE)new AlertDialog.Builder(this).setTitle("Replace portfolio from backup?")
            .setMessage("After successful password and ledger validation, all existing transactions will be replaced. Export your current ledger first if needed. Downloaded prices are not part of the backup; tap Refresh prices afterward.")
            .setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->password(uri,true)).show();
    }
    private void password(Uri uri,boolean restore) {
        LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(8));box.setBackgroundColor(surface);
        label(box,restore?"Enter the backup password.":"Use a strong password (at least 8 characters). There is no password recovery.",14,foreground);
        EditText pass=field(box,"Password","",false);pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setSaveEnabled(false);pass.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        EditText confirm=restore?null:field(box,"Confirm password","",false);
        if(confirm!=null){confirm.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);confirm.setSaveEnabled(false);confirm.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(restore?"Restore ledger":"Encrypt ledger")
            .setView(box).setNegativeButton("Cancel",null).setPositiveButton(restore?"Restore":"Save",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(app.busy){toast("Please wait for the current operation.");return;}
            if(pass.length()<8){pass.setError("Use at least 8 characters");return;}
            if(confirm!=null && !pass.getText().toString().equals(confirm.getText().toString())){confirm.setError("Passwords do not match");return;}
            char[] secret=new char[pass.length()];pass.getText().getChars(0,pass.length(),secret,0);pass.setText("");if(confirm!=null)confirm.setText("");dialog.dismiss();
            job(restore?"Decrypting and validating backup…":"Encrypting backup…",()->{
                try {
                    if(restore){
                        List<Tx> restored;try(InputStream in=getContentResolver().openInputStream(uri)){
                            if(in==null)throw new java.io.IOException("Could not open backup");restored=Backup.readEncrypted(in,secret);
                        }app.store.importTransactions(restored,true);return "Restored "+restored.size()+" transactions. Tap Refresh prices to rebuild valuations.";
                    }else{
                        try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                            if(out==null)throw new java.io.IOException("Could not open destination");Backup.writeEncrypted(out,app.store.transactions(),secret);
                        }return "Encrypted ledger backup saved. Keep your password safe.";
                    }
                }finally{Arrays.fill(secret,'\0');}
            });
        }));dialog.show();
    }
}