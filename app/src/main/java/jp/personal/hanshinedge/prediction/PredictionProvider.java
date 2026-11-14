package jp.personal.hanshinedge.prediction;
import java.util.List;
import jp.personal.hanshinedge.runtime.CapabilityGate;
/** UI consumes this provider, never calculates or invents model inputs. */
public final class PredictionProvider {
 public static final class Prediction {public String modelTag=FrozenMf0Engine.MODEL,modelAction="UNKNOWN",capabilityAction,finalAction="BLOCKED",reason,f0Status="NOT_AVAILABLE";public FrozenMf0Engine.Output model;}
 private final FrozenMf0Engine engine;
 public PredictionProvider(){this(new FrozenMf0Engine());}public PredictionProvider(FrozenMf0Engine e){engine=e;}
 public Prediction predict(CapabilityGate.RaceContext race,List<FrozenMf0Engine.Input> inputs,Long retrievedAt,long decisionAt){
  Prediction p=new Prediction();CapabilityGate.Capabilities c=CapabilityGate.evaluate(race);p.capabilityAction=c.formalPaperBuy?"ALLOW":"BLOCKED";
  if(!c.flatModel){p.reason="OBSTACLE_MODEL_BLOCKED";return p;}
  if(inputs==null||inputs.stream().anyMatch(x->x.f0==null)){p.reason=c.formalPaperBuy?"F0_NOT_READY":c.formalBlockReason;return p;}
  p.f0Status="SUPPLIED";
  if(retrievedAt==null||retrievedAt>decisionAt||decisionAt-retrievedAt>45000){p.reason="MARKET_STALE_OR_UNVERIFIED";return p;}
  p.model=engine.evaluate(inputs);p.modelAction=p.model.action;
  if(!c.formalPaperBuy){p.reason=c.formalBlockReason;return p;}
  if(p.model.rows.isEmpty()){p.reason=p.model.reason;return p;}
  p.finalAction=p.model.action;p.reason=p.model.reason;return p;
 }
}
