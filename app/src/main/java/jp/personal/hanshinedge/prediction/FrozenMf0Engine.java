package jp.personal.hanshinedge.prediction;
import java.util.*;
/** Port of the preserved HanshinCore.evaluate math; never generates F0. */
public final class FrozenMf0Engine {
 public static final String MODEL="HANSHIN-EDGE-MF0-v1.0-FROZEN";
 public static final double BUY_EV_QUOTE=1.15,BUY_EDGE_PT=3.0;
 public static final int MARKET_MAX_AGE_SEC=45;
 public static final String MODEL_HASH_STATUS="HISTORICAL_REFERENCE_NOT_REPRODUCED";
 public static final class Input {public final int horseNo;public final String name;public final Double odds,f0;public Input(int n,String name,Double odds,Double f0){horseNo=n;this.name=name;this.odds=odds;this.f0=f0;}}
 public static final class Row {public int horseNo;public String horseName;public double odds,f0,m0,mf0,edgePt,evQuote;}
 public static final class Output {public String action,reason;public List<Row> rows=new ArrayList<>();public Row top;Output(String a,String r){action=a;reason=r;}}
 public static String threshold(double ev,double edge){return ev>=BUY_EV_QUOTE&&edge>=BUY_EDGE_PT?"BUY":"PASS";}
 public Output evaluate(List<Input> inputs){
  if(inputs==null||inputs.size()<2||inputs.size()>18)return new Output("PASS","RUNNER_COUNT_INVALID");
  Set<Integer> seen=new HashSet<>();double sf=0,sm=0;
  for(Input i:inputs){if(i.f0==null)return new Output("PASS","F0_NOT_READY");if(i.horseNo<1||i.horseNo>18||!seen.add(i.horseNo)||i.odds==null||!Double.isFinite(i.odds)||i.odds<1||!Double.isFinite(i.f0)||i.f0<0)return new Output("PASS","INPUT_INVALID");sf+=i.f0;sm+=1/i.odds;}
  if(!(sf>0&&sm>0))return new Output("PASS","PROBABILITY_INVALID");
  Output out=new Output("PASS","EDGE_OR_EV_FAIL");double sum=0;
  for(Input i:inputs){Row r=new Row();r.horseNo=i.horseNo;r.horseName=i.name;r.odds=i.odds;r.f0=i.f0/sf;r.m0=(1/i.odds)/sm;r.mf0=Math.sqrt(r.f0*r.m0);sum+=r.mf0;out.rows.add(r);}
  if(!(sum>0))return new Output("PASS","MF0_INVALID");
  for(Row r:out.rows){r.mf0/=sum;r.edgePt=(r.mf0-r.m0)*100;r.evQuote=r.mf0*r.odds;}
  out.rows.sort((a,b)->Double.compare(b.mf0,a.mf0));out.top=out.rows.get(0);out.action=threshold(out.top.evQuote,out.top.edgePt);out.reason=out.action.equals("BUY")?"GATE_PASS":"EDGE_OR_EV_FAIL";return out;
 }
}
