package com.caf.btmesh;

/** A locomotion handoff owns one continuous displayed pose; source BB curves stay intact. */
public final class BtLocomotionBlend {
    private BtLocomotionBlend(){}
    public static boolean movingClip(int id){return id==2||id==4||id==23||id==24||id==25||id==26;}
    private static boolean idleClip(int id){return id==1||id==22;}
    public static double duration(int from,int to){
        if(movingClip(from)&&idleClip(to))return .36;
        if(idleClip(from)&&movingClip(to))return .28;
        if(movingClip(from)&&movingClip(to))return .22;
        return .15;
    }
    public static float apply(BbMotionPlayer motion,BbMotionPlayer.Frame from,BbMotionPlayer.Frame target,
            int outgoing,double seconds,double rate,double age,double duration,float aim,
            BbMotionPlayer.Frame advancing,BbMotionPlayer.Frame departure,BbMotionPlayer.Frame displayed){
        float u=(float)Math.max(0,Math.min(1,age/duration));
        if(u>=1){displayed.copyFrom(target);return 1;}
        if(outgoing>0){
            // The outgoing step decelerates over the blend; do not freeze a raised
            // knee at the impact frame and then snap it back to the floor.
            // This is only the outgoing STOP tail. The regular distance-driven
            // source playback stays unchanged. Retain a slowed settling step:
            // the lateral BB foot has a sharp native roll near phase .583.
            double travel=Math.max(0,age)*(1-.5*u)*Math.max(0,Math.min(.6,rate));
            motion.sample(outgoing,seconds+travel,advancing);motion.aim(advancing,aim);
            // Retain the visible interruption snapshot and add only the source
            // displacement since that instant. A second 50ms catch-up blend
            // accelerated the swinging foot by up to 1.5x at the start of a stop.
            // Reference deltas preserve C0 even when from is already a blend.
            motion.sample(outgoing,seconds,departure);motion.aim(departure,aim);
            for(int i=0;i<departure.position.length;i++){
                departure.position[i]=from.position[i]+advancing.position[i]-departure.position[i];
                departure.scale[i]=from.scale[i]*advancing.scale[i]/Math.max(1e-5,departure.scale[i]);
                departure.euler[i]=advancing.euler[i];
            }
            for(int i=0;i<departure.rotation.length;i++)departure.rotation[i].invert().mul(advancing.rotation[i]).premul(from.rotation[i]).normalize();
            motion.blend(departure,target,u,displayed);
        }else motion.blend(from,target,u,displayed);
        return u;
    }
}
