package com.caf.btmesh;

import com.google.gson.*;
import java.util.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Matrix4f;

/** Source local TRS/sequence evaluator. No authored replacement keyframes.
 * Sequence POST deltas use qBase * weightedDelta (Source SlerpBones).
 * Ground/attachment IK, procedural bones and engine bob/viewkick are separate.
 */
public final class NativeMotionPlayer {
    public enum FirstPersonState { IDLE, RELOAD, RELOAD_EMPTY, DRAW, HOLSTER, RAISE, LOWER, MELEE, SPRINT, SPRINT_RAISE }
    public record Event(String name,String options,float cycle,double seconds) {}
    public record BodyPose(String sequence,double seconds,String overlay,double overlaySeconds,double overlayDuration,float overlayWeight){
        public BodyPose(String sequence,double seconds){this(sequence,seconds,"",0,0,0);}
        public BodyPose advanced(double seconds){return new BodyPose(sequence,this.seconds+seconds,overlay,overlaySeconds+seconds,overlayDuration,overlayWeight);}
    }
    /** Value snapshot of the last evaluated original skeletal pose. Callers keep
     * one per entity/viewmodel when an action is interrupted; no cube geometry
     * or retargeted pivot is stored in this snapshot. */
    public static final class PoseSnapshot {
        private final float[] t,r,s;
        // Transition state belongs to this frozen per-entity source, never the
        // renderer-wide player. Rotation vectors may pass pi continuously.
        private final float[] bodyRelativeLog;
        private String bodyBranchKey;
        private float bodyBranchAlpha=-1;
        private boolean bodyBranchReady;
        private PoseSnapshot(int count){t=new float[count*3];r=new float[count*4];s=new float[count*3];bodyRelativeLog=new float[count*3];}
        public int nodes(){return t.length/3;}
    }
    private record Sequence(String name,int flags,int[] anims,int[] groups,int[] params,float[] keys,
                            float[] weights,JsonArray layers,JsonArray events) {}
    private final GltfSkin model;
    private final Sequence[] sequences;
    private final String[] animationNames,parameterNames;
    private final Map<String,Integer> sequenceNames=new HashMap<>();
    private final Map<String,Integer> nodeNames=new HashMap<>();
    private final Map<String,Double> strides=new HashMap<>();
    private record Attachment(int node,Matrix4f local){}
    private final Map<String,Attachment> attachments=new HashMap<>();
    private final int[] boneNodes;
    private final float[] scratch=new float[4];
    private final Quaternionf scaled=new Quaternionf();
    private final Quaternionf sampleQ1=new Quaternionf(),sampleQ2=new Quaternionf();
    private final List<Frame> framePool=new ArrayList<>();
    private int frameCursor;
    private final Frame destination,temporary;

    private final class Frame {
        final Vector3f[] t=new Vector3f[model.nodes.length],s=new Vector3f[model.nodes.length];
        final Quaternionf[] r=new Quaternionf[model.nodes.length];
        Frame(){for(int i=0;i<t.length;i++){t[i]=new Vector3f();s[i]=new Vector3f();r[i]=new Quaternionf();}reset(false);}
        void reset(boolean delta){for(int i=0;i<t.length;i++){if(delta){t[i].zero();r[i].identity();s[i].set(1);}else{t[i].set(model.nodes[i].translation);r[i].set(model.nodes[i].rotation);s[i].set(model.nodes[i].scale);}}}
        void copy(Frame x){for(int i=0;i<t.length;i++){t[i].set(x.t[i]);r[i].set(x.r[i]);s[i].set(x.s[i]);}}
        void copy(PoseSnapshot x){if(x.nodes()!=t.length)throw new IllegalArgumentException("snapshot skeleton mismatch");
            for(int i=0;i<t.length;i++){t[i].set(x.t[i*3],x.t[i*3+1],x.t[i*3+2]);r[i].set(x.r[i*4],x.r[i*4+1],x.r[i*4+2],x.r[i*4+3]);s[i].set(x.s[i*3],x.s[i*3+1],x.s[i*3+2]);}}
        PoseSnapshot snapshot(PoseSnapshot reusable){PoseSnapshot x=reusable!=null&&reusable.nodes()==t.length?reusable:new PoseSnapshot(t.length);x.bodyBranchReady=false;x.bodyBranchKey=null;x.bodyBranchAlpha=-1;for(int i=0;i<t.length;i++){
            x.t[i*3]=t[i].x;x.t[i*3+1]=t[i].y;x.t[i*3+2]=t[i].z;
            x.r[i*4]=r[i].x;x.r[i*4+1]=r[i].y;x.r[i*4+2]=r[i].z;x.r[i*4+3]=r[i].w;
            x.s[i*3]=s[i].x;x.s[i*3+1]=s[i].y;x.s[i*3+2]=s[i].z;
        }return x;}
    }

    public NativeMotionPlayer(GltfSkin model,JsonObject metadata){
        this.model=model;
        if(metadata.has("locomotion"))for(var item:metadata.getAsJsonObject("locomotion").entrySet())
            strides.put(item.getKey(),item.getValue().getAsJsonObject().get("stride_metres").getAsDouble());
        for(int i=0;i<model.nodes.length;i++)nodeNames.putIfAbsent(model.nodes[i].name,i);
        JsonArray bones=metadata.getAsJsonArray("bones");boneNodes=new int[bones.size()];
        for(int i=0;i<boneNodes.length;i++)boneNodes[i]=nodeNames.getOrDefault(bones.get(i).getAsJsonObject().get("name").getAsString(),-1);
        for(JsonElement raw:metadata.getAsJsonArray("attachments")){
            JsonObject a=raw.getAsJsonObject();int bi=a.get("localbone").getAsInt();if(bi<0||bi>=boneNodes.length||boneNodes[bi]<0)continue;
            float[] v=floats(a.getAsJsonArray("local"));Matrix4f m=new Matrix4f().set(
                v[0],v[4],v[8],0,v[1],v[5],v[9],0,v[2],v[6],v[10],0,v[3],v[7],v[11],1);
            attachments.putIfAbsent(a.get("name").getAsString(),new Attachment(boneNodes[bi],m));
        }
        JsonArray animations=metadata.getAsJsonArray("animations");animationNames=new String[animations.size()];
        for(int i=0;i<animations.size();i++)animationNames[i]=animations.get(i).getAsJsonObject().get("name").getAsString();
        JsonArray parameters=metadata.getAsJsonArray("pose_parameters");parameterNames=new String[parameters.size()];
        for(int i=0;i<parameters.size();i++)parameterNames[i]=parameters.get(i).getAsJsonObject().get("name").getAsString();
        JsonArray seq=metadata.getAsJsonArray("sequences");sequences=new Sequence[seq.size()];
        for(int i=0;i<seq.size();i++){
            JsonObject x=seq.get(i).getAsJsonObject();String name=x.get("name").getAsString();
            sequences[i]=new Sequence(name,x.get("flags").getAsInt(),ints(x.getAsJsonArray("animations")),
                ints(x.getAsJsonArray("groupsize")),ints(x.getAsJsonArray("paramindex")),
                x.has("posekeys")?floats(x.getAsJsonArray("posekeys")):new float[0],floats(x.getAsJsonArray("weights")),
                x.getAsJsonArray("layers"),x.getAsJsonArray("events"));sequenceNames.put(name,i);
        }
        destination=new Frame();temporary=new Frame();
    }

    public boolean hasSequence(String name){Integer i=sequenceNames.get(name);return i!=null&&available(i,new HashSet<>());}
    private boolean available(int i,Set<Integer> seen){
        if(!seen.add(i))return false;Sequence s=sequences[i];
        for(int a:s.anims)if(a<0||a>=animationNames.length||!model.clips.containsKey(animationNames[a]))return false;
        for(JsonElement raw:s.layers)if(!available(raw.getAsJsonObject().get("iSequence").getAsInt(),seen))return false;
        seen.remove(i);return true;
    }

    /** Time uses original clip seconds. Use sampleTimed when gameplay defines duration. */
    public void sample(String sequence,double seconds,Map<String,Float> parameters,GltfSkin.Pose out){
        frameCursor=0;destination.reset(false);accumulate(destination,index(sequence),seconds,1,parameters,0);commit(destination,out);
    }
    public void sampleTimed(String sequence,double elapsed,double targetDuration,Map<String,Float> parameters,GltfSkin.Pose out){
        int i=index(sequence);double nativeDuration=duration(i,parameters);
        sample(sequence,targetDuration>0?elapsed*nativeDuration/targetDuration:elapsed,parameters,out);
    }
    /** Play a contiguous source segment without changing any of its authored
     * keyframes. Useful when MC hotdrop impact must align to Source event .837. */
    public void sampleSegment(String sequence,double elapsed,double targetDuration,double startCycle,double endCycle,
                              Map<String,Float> parameters,GltfSkin.Pose out){
        if(startCycle<0||endCycle>1||endCycle<startCycle||targetDuration<=0)
            throw new IllegalArgumentException("invalid original sequence segment");
        double u=Math.max(0,Math.min(1,elapsed/targetDuration));
        sample(sequence,(startCycle+(endCycle-startCycle)*u)*duration(index(sequence),parameters),parameters,out);
    }
    /** Preserves locomotion while applying an original weighted gesture sequence. */
    public void sampleLayered(String base,double baseSeconds,String overlay,double overlayElapsed,
                              double overlayDuration,float overlayWeight,Map<String,Float> parameters,GltfSkin.Pose out){
        frameCursor=0;destination.reset(false);accumulate(destination,index(base),baseSeconds,1,parameters,0);
        int i=index(overlay);double nativeDuration=duration(i,parameters);
        double seconds=overlayDuration>0?overlayElapsed*nativeDuration/overlayDuration:overlayElapsed;
        if(overlayElapsed>=0&&(overlayDuration<=0||overlayElapsed<=overlayDuration))
            accumulate(destination,i,seconds,clamp(overlayWeight),parameters,0);
        commit(destination,out);
    }
    /** Blend evaluated old/new sequence poses, including their own original layers. */
    public void sampleTransition(String from,double fromSeconds,String to,double toSeconds,float alpha,
                                 Map<String,Float> parameters,GltfSkin.Pose out){
        frameCursor=0;destination.reset(false);temporary.reset(false);
        accumulate(destination,index(from),fromSeconds,1,parameters,0);
        accumulate(temporary,index(to),toSeconds,1,parameters,0);
        float u=clamp(alpha);for(int n=0;n<model.nodes.length;n++){
            destination.t[n].lerp(temporary.t[n],u);destination.r[n].slerp(temporary.r[n],u).normalize();destination.s[n].lerp(temporary.s[n],u);
        }commit(destination,out);
    }
    /** Crossfades complete evaluated original states, including weighted gestures,
     * so ending a reload/melee/action cannot snap directly back to locomotion. */
    public void sampleBodyTransition(BodyPose from,BodyPose to,float alpha,Map<String,Float> parameters,GltfSkin.Pose out){
        frameCursor=0;destination.reset(false);temporary.reset(false);
        evaluateBody(destination,from,parameters);evaluateBody(temporary,to,parameters);
        lerp(destination,temporary,clamp(alpha));commit(destination,out);
    }
    /** Captures the displayed source pose at interruption. New actions can
     * interrupt an existing transition without jumping to its raw source. */
    public PoseSnapshot snapshotCurrent(){return destination.snapshot(null);}
    /** Reuses the caller's arrays when the skeleton size is unchanged. Keep
     * separate lastDisplayed/fromTransition buffers; reusing the transition's
     * own source while it is playing would overwrite that frozen source. */
    public PoseSnapshot snapshotCurrent(PoseSnapshot reusable){return destination.snapshot(reusable);}
    public void sampleBodyFromSnapshot(PoseSnapshot from,BodyPose to,float alpha,Map<String,Float> parameters,GltfSkin.Pose out){
        frameCursor=0;destination.copy(from);temporary.reset(false);evaluateBody(temporary,to,parameters);
        float u=clamp(alpha);String key=to.sequence+"|"+to.overlay;
        boolean continuing=from.bodyBranchReady&&Objects.equals(key,from.bodyBranchKey)&&u>=from.bodyBranchAlpha;
        Quaternionf relative=new Quaternionf();Vector3f vector=new Vector3f();
        for(int n=0;n<model.nodes.length;n++){
            destination.t[n].lerp(temporary.t[n],u);destination.s[n].lerp(temporary.s[n],u);
            relative.set(destination.r[n]).invert().mul(temporary.r[n]).normalize();
            continuousRelativeLog(relative,from.bodyRelativeLog,n*3,continuing,vector);
            from.bodyRelativeLog[n*3]=vector.x;from.bodyRelativeLog[n*3+1]=vector.y;from.bodyRelativeLog[n*3+2]=vector.z;
            float length=vector.length();
            if(length>1e-7f){float half=.5f*u*length,factor=(float)Math.sin(half)/length;relative.set(vector.x*factor,vector.y*factor,vector.z*factor,(float)Math.cos(half));destination.r[n].mul(relative).normalize();}
        }
        from.bodyBranchReady=true;from.bodyBranchKey=key;from.bodyBranchAlpha=u;commit(destination,out);
    }
    /** Select the equivalent axis-angle nearest the preceding target log. A
     * fresh entry takes the shortest path once; it never reselects at pi as
     * the moving target crosses the frozen source's antipodal boundary.
     * Calls for one transition must be chronological, as rendering already is;
     * rewinding alpha or changing state resets the branch deliberately. */
    private static void continuousRelativeLog(Quaternionf q,float[] previous,int offset,boolean continuing,Vector3f out){
        float norm=(float)Math.sqrt(q.x*q.x+q.y*q.y+q.z*q.z);
        if(norm<1e-7f){
            if(!continuing){out.zero();return;}
            out.set(previous[offset],previous[offset+1],previous[offset+2]);float old=out.length();
            if(old<1e-7f){out.zero();return;}out.mul((float)(Math.rint(old/(2*Math.PI))*2*Math.PI)/old);return;
        }
        float angle=2*(float)Math.atan2(norm,q.w),ax=q.x/norm,ay=q.y/norm,az=q.z/norm;
        if(!continuing){if(angle>Math.PI)angle-=2*(float)Math.PI;out.set(ax*angle,ay*angle,az*angle);return;}
        float best=Float.POSITIVE_INFINITY,bestAngle=angle;
        float projection=ax*previous[offset]+ay*previous[offset+1]+az*previous[offset+2];
        int center=(int)Math.rint((projection-angle)/(2*Math.PI));
        for(int turn=center-1;turn<=center+1;turn++){
            float candidate=angle+turn*2*(float)Math.PI,dx=ax*candidate-previous[offset],dy=ay*candidate-previous[offset+1],dz=az*candidate-previous[offset+2],distance=dx*dx+dy*dy+dz*dz;
            if(distance<best){best=distance;bestAngle=candidate;}
        }
        out.set(ax*bestAngle,ay*bestAngle,az*bestAngle);
    }
    private void evaluateBody(Frame out,BodyPose body,Map<String,Float> params){
        accumulate(out,index(body.sequence),body.seconds,1,params,0);
        if(!body.overlay.isEmpty()&&body.overlayWeight>0){int overlay=index(body.overlay);double nativeSeconds=body.overlayDuration>0?body.overlaySeconds*duration(overlay,params)/body.overlayDuration:body.overlaySeconds;
            accumulate(out,overlay,nativeSeconds,clamp(body.overlayWeight),params,0);}
    }
    /** Contact envelope follows original alternating footfall cycles. No world
     * footstep timing is invented: each stance starts at the native event and
     * fades after the opposite foot lands. */
    public float footContact(String sequence,double unwrappedCycle,String side){
        Sequence s=sequences[index(sequence)];String event=side.equals("left")?"AE_FOOTSTEP_L":"AE_FOOTSTEP_R";
        double cycle=unwrappedCycle-Math.floor(unwrappedCycle),last=-10,nextOther=10;
        for(JsonElement raw:s.events){var e=raw.getAsJsonObject();if(!e.get("name").getAsString().equals(event))continue;double at=e.get("cycle").getAsDouble();if(at>cycle)at-=1;last=Math.max(last,at);}
        if(last<-2)return 1;
        for(JsonElement raw:s.events){var e=raw.getAsJsonObject();String n=e.get("name").getAsString();if(!n.startsWith("AE_FOOTSTEP")||n.equals(event))continue;double at=e.get("cycle").getAsDouble();while(at>last+1)at-=1;while(at<=last)at+=1;nextOther=Math.min(nextOther,at);}
        double rise=Math.max(0,Math.min(1,(cycle-last)/.015)),fall=Math.max(0,Math.min(1,(nextOther+.025-cycle)/.04));
        float u=(float)Math.min(rise,fall);return u*u*(3-2*u);
    }

    /** Original 1P base posture + POST idle/fire deltas. Render-engine bob and viewkick are not baked here.
     * ads_in is retained as the absolute reference pose: using the skeleton bind pose
     * would put the weapon at origin and spread the hands. State selection is a port,
     * while all curves/weights/events come from the original MDL.
     */
    public void sampleFirstPerson(FirstPersonState state,double stateSeconds,float adsBlend,
                                  double idleSeconds,double shotSeconds,GltfSkin.Pose out){
        frameCursor=0;destination.reset(false);evaluateFirstPerson(destination,state,stateSeconds,adsBlend,idleSeconds,shotSeconds);commit(destination,out);
    }
    public void sampleFirstPersonFromSnapshot(PoseSnapshot from,FirstPersonState state,double stateSeconds,float adsBlend,
                                               double idleSeconds,double shotSeconds,float alpha,GltfSkin.Pose out){
        frameCursor=0;destination.copy(from);temporary.reset(false);
        evaluateFirstPerson(temporary,state,stateSeconds,adsBlend,idleSeconds,shotSeconds);
        lerp(destination,temporary,clamp(alpha));commit(destination,out);
    }
    /** Apply immediately after sampleFirstPerson[FromSnapshot]. Original Vortex
     * FP sequences carry a left-chain-only mask; weapon/right hand/camera stay
     * on the original XO16 state. Entry/exit weight is an explicit MC crossfade.
     */
    public void applyFirstPersonOffhand(String sequence,double seconds,float weight,GltfSkin.Pose out){
        if(weight<=0)return;frameCursor=0;
        accumulate(destination,index(sequence),seconds,clamp(weight),Map.of(),0);commit(destination,out);
    }
    private void evaluateFirstPerson(Frame output,FirstPersonState state,double stateSeconds,float adsBlend,double idleSeconds,double shotSeconds){
        Map<String,Float> params=Map.of("ads_blend",clamp(adsBlend));
        String name=switch(state){
            case RELOAD->"reload_seq";case RELOAD_EMPTY->"reload_empty_seq";case DRAW->"draw_seq";
            case HOLSTER->"holster_seq";case RAISE->"raise_seq";case LOWER->"lower_seq";case MELEE->"melee_seq";
            case SPRINT->"sprint_seq";case SPRINT_RAISE->"sprintraise_seq";default->"ads_in_seq";};
        int seq=index(name);double seconds=stateSeconds;
        if(state==FirstPersonState.IDLE)seconds=clamp(adsBlend)*duration(seq,params);
        else if(state==FirstPersonState.RELOAD||state==FirstPersonState.RELOAD_EMPTY)seconds*=duration(seq,params)/2.6;
        else if(state==FirstPersonState.RAISE)seconds*=duration(seq,params)/.4;
        else if(state==FirstPersonState.LOWER)seconds*=duration(seq,params)/.1;
        accumulate(output,seq,seconds,1,params,0);
        if(state==FirstPersonState.IDLE){
            accumulate(output,index("idle_seq"),idleSeconds,1,params,0);
            if(shotSeconds>=0&&shotSeconds<=duration(index("attack_seq"),params))
                accumulate(output,index("attack_seq"),shotSeconds,1,params,0);
        }
    }

    public double sequenceDuration(String name,Map<String,Float> parameters){return duration(index(name),parameters);}
    /** Full original clip travel, decoded from v53 frame movement in Source units. */
    public double strideMetres(String name){return strides.getOrDefault(name,0.0);}
    public boolean isLooping(String name){return (sequences[index(name)].flags&1)!=0;}
    /** Result includes the exported scene wrapper. Pass the same model-space/world
     * transform as mesh rendering once; do not apply wrapper scale a second time. */
    public boolean attachment(GltfSkin.Pose pose,String name,Matrix4f result){
        Attachment a=attachments.get(name);if(a==null)return false;result.set(pose.world[a.node]).mul(a.local);return true;
    }
    /** Events are crossed once, on the same normalized sequence clock as animation.
     * Starting previousElapsed below zero includes the frame-zero effects/sounds.
     */
    public List<Event> events(String sequence,double previousElapsed,double elapsed,double targetDuration){
        Sequence s=sequences[index(sequence)];double duration=targetDuration>0?targetDuration:duration(index(sequence),Map.of());
        List<Event> result=new ArrayList<>();
        for(JsonElement raw:s.events){JsonObject e=raw.getAsJsonObject();float cycle=e.get("cycle").getAsFloat();double t=cycle*duration;
            if(t>previousElapsed&&t<=elapsed)result.add(new Event(e.get("name").getAsString(),e.get("options").getAsString(),cycle,t));}
        result.sort(Comparator.comparingDouble(Event::seconds));return result;
    }
    /** Loop-aware event crossing using the exact unwrapped normalized locomotion
     * clock used to sample the body. Returned seconds are absolute source seconds.
     * A stationary phase never repeats footsteps; crossing cycle 1 includes cycle 0
     * of the next loop exactly once. Caller resets previous on state/teleport.
     */
    public List<Event> eventsCycles(String sequence,double previousCycle,double cycle){
        if(cycle<=previousCycle)return List.of();
        if(cycle-previousCycle>8)throw new IllegalArgumentException("event clock jumped more than eight loops");
        Sequence s=sequences[index(sequence)];double d=duration(index(sequence),Map.of());List<Event> result=new ArrayList<>();
        long first=(long)Math.floor(previousCycle),last=(long)Math.floor(cycle);
        for(long loop=first;loop<=last;loop++)for(JsonElement raw:s.events){
            JsonObject e=raw.getAsJsonObject();float localCycle=e.get("cycle").getAsFloat();double at=loop+localCycle;
            if(at>previousCycle&&at<=cycle)result.add(new Event(e.get("name").getAsString(),e.get("options").getAsString(),localCycle,at*d));
        }
        result.sort(Comparator.comparingDouble(Event::seconds));return result;
    }

    private void accumulate(Frame dst,int seq,double seconds,float weight,Map<String,Float> params,int depth){
        if(depth>16)throw new IllegalStateException("sequence layer cycle");
        Sequence s=sequences[seq];Frame sampled=evaluate(s,seconds,params);
        boolean delta=(s.flags&4)!=0,post=(s.flags&0x10)!=0;
        blend(dst,sampled,weight,s.weights,delta,post);
        double dur=duration(seq,params);float cycle=dur>0?(float)(seconds/dur):0;
        if((s.flags&1)!=0)cycle-= (float)Math.floor(cycle);else cycle=clamp(cycle);
        for(JsonElement raw:s.layers){JsonObject l=raw.getAsJsonObject();int flags=l.get("flags").getAsInt();
            // Original BT locomotion layers in this package have flags=0 and all
            // range values=0. Explicitly reject unevaluated specialized semantics.
            if(flags!=0)throw new IllegalStateException("unsupported sequence autolayer flags "+flags);
            float start=l.get("start").getAsFloat(),peak=l.get("peak").getAsFloat(),tail=l.get("tail").getAsFloat(),end=l.get("end").getAsFloat();
            float layerWeight=weight;
            if(end>start){if(cycle<start||cycle>end)continue;if(cycle<peak&&peak>start)layerWeight*= (cycle-start)/(peak-start);else if(cycle>tail&&end>tail)layerWeight*= (end-cycle)/(end-tail);}
            int child=l.get("iSequence").getAsInt();accumulate(dst,child,cycle*duration(child,params),layerWeight,params,depth+1);
        }
    }

    private Frame evaluate(Sequence s,double seconds,Map<String,Float> params){
        boolean delta=(s.flags&4)!=0;int nx=Math.max(1,s.groups[0]),ny=Math.max(1,s.groups[1]);
        Axis x=axis(s,0,nx,0,params),y=axis(s,1,ny,nx,params);
        Frame a=clip(s.anims[x.lo+y.lo*nx],seconds,(s.flags&1)!=0,delta);
        if(x.hi!=x.lo)lerp(a,clip(s.anims[x.hi+y.lo*nx],seconds,(s.flags&1)!=0,delta),x.u);
        if(y.hi!=y.lo){Frame b=clip(s.anims[x.lo+y.hi*nx],seconds,(s.flags&1)!=0,delta);
            if(x.hi!=x.lo)lerp(b,clip(s.anims[x.hi+y.hi*nx],seconds,(s.flags&1)!=0,delta),x.u);lerp(a,b,y.u);}
        return a;
    }
    private record Axis(int lo,int hi,float u){}
    private Axis axis(Sequence s,int axis,int size,int keyOffset,Map<String,Float> params){
        if(size<=1)return new Axis(0,0,0);int parameter=s.params[axis];
        float value=parameter>=0&&parameter<parameterNames.length?params.getOrDefault(parameterNames[parameter],0f):0;
        if(s.keys.length<keyOffset+size)throw new IllegalStateException("missing original pose keys");
        boolean ascending=s.keys[keyOffset+size-1]>=s.keys[keyOffset];
        if(ascending?value<=s.keys[keyOffset]:value>=s.keys[keyOffset])return new Axis(0,0,0);
        for(int k=1;k<size;k++)if(ascending?value<=s.keys[keyOffset+k]:value>=s.keys[keyOffset+k]){
            float lo=s.keys[keyOffset+k-1],hi=s.keys[keyOffset+k];return new Axis(k-1,k,hi==lo?0:clamp((value-lo)/(hi-lo)));}
        return new Axis(size-1,size-1,0);
    }
    private Frame clip(int animation,double seconds,boolean loop,boolean delta){
        GltfSkin.Clip clip=model.clips.get(animationNames[animation]);
        if(clip==null)throw new IllegalStateException("Missing ORIGINAL clip "+animationNames[animation]);
        Frame f=nextFrame(delta);float time=clock(seconds,clip.duration(),loop);
        for(GltfSkin.Channel c:clip.channels()){sampleChannel(c,time,scratch);int n=c.node();switch(c.path()){
            case "translation"->f.t[n].set(scratch[0],scratch[1],scratch[2]);case "rotation"->f.r[n].set(scratch[0],scratch[1],scratch[2],scratch[3]).normalize();case "scale"->f.s[n].set(scratch[0],scratch[1],scratch[2]);}}
        return f;
    }
    private void blend(Frame dst,Frame src,float weight,float[] weights,boolean delta,boolean post){
        for(int bi=0;bi<boneNodes.length;bi++){int n=boneNodes[bi];if(n<0)continue;float w=clamp(weight*(bi<weights.length?weights[bi]:1));if(w<=0)continue;
            if(delta){dst.t[n].fma(w,src.t[n]);quaternionScale(src.r[n],w,scaled);if(post)dst.r[n].mul(scaled);else dst.r[n].premul(scaled);dst.r[n].normalize();
                // TF2 scale delta is represented around identity. Ordinary BT/XO16
                // aim/fire deltas are unit scale; this preserves their exact base.
                dst.s[n].mul(1+(src.s[n].x-1)*w,1+(src.s[n].y-1)*w,1+(src.s[n].z-1)*w);
            }else{dst.t[n].lerp(src.t[n],w);dst.r[n].slerp(src.r[n],w).normalize();dst.s[n].lerp(src.s[n],w);}
        }
    }
    private static void quaternionScale(Quaternionf q,float weight,Quaternionf out){
        float sin=(float)Math.min(1,Math.sqrt(q.x*q.x+q.y*q.y+q.z*q.z));
        float scaledSin=(float)Math.sin(Math.asin(sin)*weight),ratio=sin>1e-12f?scaledSin/sin:weight;
        out.set(q.x*ratio,q.y*ratio,q.z*ratio,(q.w<0?-1:1)*(float)Math.sqrt(Math.max(0,1-scaledSin*scaledSin))).normalize();
    }
    private void lerp(Frame a,Frame b,float u){
        if(u<=0)return;if(u>=1){a.copy(b);return;}
        for(int i=0;i<a.t.length;i++){a.t[i].lerp(b.t[i],u);a.r[i].slerp(b.r[i],u).normalize();a.s[i].lerp(b.s[i],u);}
    }
    private Frame nextFrame(boolean delta){if(frameCursor>=framePool.size())framePool.add(new Frame());Frame f=framePool.get(frameCursor++);f.reset(delta);return f;}
    private void commit(Frame f,GltfSkin.Pose out){out.resetLocal();for(int n=0;n<model.nodes.length;n++)out.setLocal(n,f.t[n],f.r[n],f.s[n]);out.finish();}
    private int index(String name){Integer i=sequenceNames.get(name);if(i==null)throw new IllegalArgumentException("No original sequence "+name);return i;}
    private double duration(int seq,Map<String,Float> params){Sequence s=sequences[seq];double d=0;for(int a:s.anims){GltfSkin.Clip c=model.clips.get(animationNames[a]);if(c!=null)d=Math.max(d,c.duration());}return d;}
    private static float clock(double seconds,float duration,boolean loop){if(duration<=0)return 0;double t=loop?seconds%duration:Math.max(0,Math.min(seconds,duration));if(t<0)t+=duration;return (float)t;}
    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
    private static int[] ints(JsonArray a){int[] v=new int[a.size()];for(int i=0;i<v.length;i++)v[i]=a.get(i).getAsInt();return v;}
    private static float[] floats(JsonArray a){float[] v=new float[a.size()];for(int i=0;i<v.length;i++)v[i]=a.get(i).getAsFloat();return v;}
    private void sampleChannel(GltfSkin.Channel c,float time,float[] out){
        float[] ts=c.time(),v=c.values();int n=ts.length;if(n==0)throw new IllegalStateException("empty native channel");
        int at=Arrays.binarySearch(ts,time),hi=at>=0?at:Math.min(n-1,-at-1),lo=at>=0?hi:Math.max(0,hi-1);
        if(time>=ts[n-1])lo=hi=n-1;float u=hi==lo?0:(time-ts[lo])/(ts[hi]-ts[lo]);if(c.interpolation().equals("STEP"))u=0;
        if(!c.interpolation().equals("LINEAR")&&!c.interpolation().equals("STEP"))throw new IllegalStateException("Native clips expected linear TRS");
        int w=c.width(),a=lo*w,b=hi*w;
        if(w==4){sampleQ1.set(v[a],v[a+1],v[a+2],v[a+3]);sampleQ2.set(v[b],v[b+1],v[b+2],v[b+3]);sampleQ1.slerp(sampleQ2,u).normalize();out[0]=sampleQ1.x;out[1]=sampleQ1.y;out[2]=sampleQ1.z;out[3]=sampleQ1.w;}
        else for(int k=0;k<w;k++)out[k]=v[a+k]*(1-u)+v[b+k]*u;
    }
}
