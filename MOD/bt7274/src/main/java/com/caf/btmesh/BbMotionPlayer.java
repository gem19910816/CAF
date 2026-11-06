package com.caf.btmesh;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.joml.Quaternionf;
import org.joml.Quaterniond;
import org.joml.Vector3f;

/** Direct playback of approved Blockbench local numeric Euler curves.
 * No source rig, Bedrock reflection, quaternion key interpolation or IK is applied. */
public final class BbMotionPlayer {
    public record Bone(String name,String uuid,int node,double[] position,double[] euler,double[] scale){}
    public record Track(int bone,int channel,double[] times,double[] values){}
    public record Clip(int id,String name,String uuid,double duration,int loop,List<Track> tracks){}
    public final GltfSkin model;
    public final Bone[] bones;
    public final Map<Integer,Clip> clips=new LinkedHashMap<>();
    public final String sourceSha256;
    private final Frame reference;
    private final Quaterniond eulerScratch=new Quaterniond();
    private final Quaternionf deltaScratch=new Quaternionf(),weightedScratch=new Quaternionf();
    public static final class Frame {
        public final double[] position,euler,scale;
        public final Quaternionf[] rotation;
        public Frame(int count){position=new double[count*3];euler=new double[count*3];scale=new double[count*3];rotation=new Quaternionf[count];for(int i=0;i<count;i++)rotation[i]=new Quaternionf();}
        public void copyFrom(Frame b){System.arraycopy(b.position,0,position,0,position.length);System.arraycopy(b.euler,0,euler,0,euler.length);System.arraycopy(b.scale,0,scale,0,scale.length);for(int i=0;i<rotation.length;i++)rotation[i].set(b.rotation[i]);}
    }
    public BbMotionPlayer(GltfSkin model,byte[] binary)throws IOException {
        this.model=model;
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(binary))){
            if(!Arrays.equals(in.readNBytes(8),"BTBBC001".getBytes(StandardCharsets.US_ASCII)))throw new IOException("Unknown BB motion binary");
            sourceSha256=readString(in);int count=bounded(in.readInt(),1,1024);bones=new Bone[count];
            for(int i=0;i<count;i++){
                String name=readString(in),uuid=readString(in);int node=-1;
                for(int n=0;n<model.nodes.length;n++)if(model.nodes[n].name.equals("cube_"+name)){node=n;break;}
                if(node<0)throw new IOException("BB bone missing in geometry: "+name);
                bones[i]=new Bone(name,uuid,node,read3(in),read3(in),read3(in));
            }
            int countClips=bounded(in.readInt(),1,1000);
            for(int c=0;c<countClips;c++){
                String name=readString(in),uuid=readString(in);double duration=finite(in.readDouble());int loop=in.readUnsignedByte(),countTracks=bounded(in.readInt(),0,count*3);List<Track> tracks=new ArrayList<>();
                for(int j=0;j<countTracks;j++){
                    int bone=bounded(in.readInt(),0,count-1),channel=bounded(in.readUnsignedByte(),0,2),keys=bounded(in.readInt(),1,1000000);double[] times=new double[keys],values=new double[keys*3];
                    for(int k=0;k<keys;k++){times[k]=in.readDouble();for(int a=0;a<3;a++)values[k*3+a]=finite(in.readDouble());if(!Double.isFinite(times[k])||(k>0&&times[k]<times[k-1]))throw new IOException("Invalid BB key times");}
                    tracks.add(new Track(bone,channel,times,values));
                }
                int id=Integer.parseInt(name.substring(0,2));clips.put(id,new Clip(id,name,uuid,duration,loop,List.copyOf(tracks)));
            }
            if(in.available()!=0)throw new IOException("Trailing BB binary data");
        }
        reference=newFrame();sample(1,0,reference);
    }
    public Frame newFrame(){return new Frame(bones.length);}
    public double duration(int id){return clip(id).duration;}
    public Clip clip(int id){Clip c=clips.get(id);if(c==null)throw new IllegalArgumentException("Missing approved BB animation "+id);return c;}
    public void sample(int id,double seconds,Frame out){
        Clip c=clip(id);double time=c.loop==1&&c.duration>0?seconds%c.duration:Math.max(0,Math.min(seconds,c.duration));if(time<0)time+=c.duration;
        for(int i=0;i<bones.length;i++){Bone b=bones[i];System.arraycopy(b.position,0,out.position,i*3,3);System.arraycopy(b.euler,0,out.euler,i*3,3);System.arraycopy(b.scale,0,out.scale,i*3,3);}
        for(Track track:c.tracks){double[] dest=track.channel==0?out.position:track.channel==1?out.euler:out.scale;int n=track.times.length,at=Arrays.binarySearch(track.times,time),upper=at>=0?at:Math.min(n-1,-at-1),lower=at>=0?at:Math.max(0,upper-1);if(time>=track.times[n-1])lower=upper=n-1;
            // Match the editor's exact-time snapping tolerance, not its UI FPS.
            if(lower!=upper&&Math.abs(track.times[lower]-time)<=1.0/1200)upper=lower;
            else if(lower!=upper&&Math.abs(track.times[upper]-time)<=1.0/1200)lower=upper;
            double dt=track.times[upper]-track.times[lower],u=dt<=0?0:Math.max(0,Math.min(1,(time-track.times[lower])/dt));
            for(int a=0;a<3;a++){double v=track.values[lower*3+a]+(track.values[upper*3+a]-track.values[lower*3+a])*u;int target=track.bone*3+a;if(track.channel==0)dest[target]+=v;else if(track.channel==1)dest[target]+=Math.toRadians(v);else dest[target]*=v==0?1e-5:v;}
        }
        updateQuaternions(out);
    }
    private void updateQuaternions(Frame frame){for(int i=0;i<bones.length;i++)eulerScratch.rotationZYX(frame.euler[i*3+2],frame.euler[i*3+1],frame.euler[i*3]).get(frame.rotation[i]);}
    public void blend(Frame from,Frame to,float progress,Frame out){
        double u=Math.max(0,Math.min(1,progress));u=u*u*(3-2*u);
        for(int i=0;i<out.position.length;i++){out.position[i]=from.position[i]+(to.position[i]-from.position[i])*u;out.scale[i]=from.scale[i]+(to.scale[i]-from.scale[i])*u;out.euler[i]=to.euler[i];}
        for(int i=0;i<bones.length;i++)out.rotation[i].set(from.rotation[i]).slerp(to.rotation[i],(float)u);
    }
    public void aim(Frame out,float degrees){
        for(int i=0;i<bones.length;i++)if(bones[i].name.equals("torso")){out.rotation[i].rotateLocalX((float)Math.toRadians(-Math.max(-35,Math.min(35,degrees))));break;}
    }
    public void overlay(Frame base,Frame action,Set<String> names,float weight){
        for(int i=0;i<bones.length;i++)if(names.contains(bones[i].name)){
            for(int a=0;a<3;a++){int k=i*3+a;base.position[k]+=(action.position[k]-reference.position[k])*weight;base.scale[k]*=1+(action.scale[k]/reference.scale[k]-1)*weight;}
            deltaScratch.set(reference.rotation[i]).invert().mul(action.rotation[i]);weightedScratch.identity().slerp(deltaScratch,weight);base.rotation[i].mul(weightedScratch);
        }
    }
    public void submit(Frame frame,GltfSkin.Pose pose){pose.resetLocal();Vector3f p=new Vector3f(),s=new Vector3f();for(int i=0;i<bones.length;i++){int k=i*3;pose.setLocal(bones[i].node,p.set((float)frame.position[k],(float)frame.position[k+1],(float)frame.position[k+2]),frame.rotation[i],s.set((float)frame.scale[k],(float)frame.scale[k+1],(float)frame.scale[k+2]));}pose.finish();}
    private static String readString(DataInputStream in)throws IOException {int n=bounded(in.readInt(),0,1<<20);byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();return new String(b,StandardCharsets.UTF_8);}
    private static double[] read3(DataInputStream in)throws IOException {return new double[]{finite(in.readDouble()),finite(in.readDouble()),finite(in.readDouble())};}
    private static double finite(double x)throws IOException {if(!Double.isFinite(x))throw new IOException("Nonfinite BB value");return x;}
    private static int bounded(int x,int min,int max)throws IOException {if(x<min||x>max)throw new IOException("BB binary bounds: "+x);return x;}
}
