package com.caf.btmesh;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** glTF 2.0 / GLB loader and independent skeletal animation sampler. No cube conversion. */
public final class GltfSkin {
    public record CubeRange(int firstIndex,int count,float diagonal,String authorUuid,boolean emissive,String lodHint){}
    public record Primitive(float[] positions,float[] normals,float[] uv,int[] joints,float[] weights,int[] indices,int material,int node,int skin,List<CubeRange> cubeRanges){}
    public record Material(float r,float g,float b,float a,String texture,boolean doubleSided,String emissiveTexture,float er,float eg,float eb){}
    public static final class Node {
        public final String name;public int parent=-1;public final int[] children;
        public final Vector3f translation,scale;public final Quaternionf rotation;public final Matrix4f matrix;
        Node(String name,int[] children,Vector3f t,Quaternionf r,Vector3f s,Matrix4f matrix){this.name=name;this.children=children;translation=t;rotation=r;scale=s;this.matrix=matrix;}
    }
    public record Skin(int[] joints,Matrix4f[] inverseBind){}
    public record Channel(int node,String path,String interpolation,float[] time,float[] values,int width){}
    public record Clip(String name,float duration,List<Channel> channels){}
    public final Node[] nodes;public final Skin[] skins;public final List<Primitive> primitives;
    public final List<Material> materials;public final Map<String,Clip> clips;public final List<byte[]> images;
    private final JsonObject json;private final ByteBuffer[] buffers;
    private GltfSkin(JsonObject json,ByteBuffer[] buffers) throws IOException {
        this.json=json;this.buffers=buffers;
        var ns=array(json,"nodes");nodes=new Node[ns.size()];
        for(int i=0;i<nodes.length;i++){
            var n=ns.get(i).getAsJsonObject();float[] t=floats(n,"translation",new float[]{0,0,0}),r=floats(n,"rotation",new float[]{0,0,0,1}),s=floats(n,"scale",new float[]{1,1,1});
            Matrix4f matrix=null;if(n.has("matrix"))matrix=new Matrix4f().set(floats(n,"matrix",null));
            nodes[i]=new Node(string(n,"name","node_"+i),ints(array(n,"children")),new Vector3f(t),new Quaternionf(r[0],r[1],r[2],r[3]),new Vector3f(s),matrix);
        }
        for(int i=0;i<nodes.length;i++)for(int child:nodes[i].children){if(child<0||child>=nodes.length)throw new IOException("invalid child");nodes[child].parent=i;}
        var ss=array(json,"skins");skins=new Skin[ss.size()];
        for(int i=0;i<skins.length;i++){
            var s=ss.get(i).getAsJsonObject();int[] joints=ints(s.getAsJsonArray("joints"));Matrix4f[] ib=new Matrix4f[joints.length];
            float[] matrices=s.has("inverseBindMatrices")?accessor(s.get("inverseBindMatrices").getAsInt()):null;
            for(int j=0;j<joints.length;j++){ib[j]=new Matrix4f();if(matrices!=null)ib[j].set(matrices,j*16);}
            skins[i]=new Skin(joints,ib);
        }
        materials=new ArrayList<>();
        for(var raw:array(json,"materials")){
            var m=raw.getAsJsonObject();var p=m.has("pbrMetallicRoughness")?m.getAsJsonObject("pbrMetallicRoughness"):new JsonObject();
            float[] c=floats(p,"baseColorFactor",new float[]{1,1,1,1});String texture=null;
            if(p.has("baseColorTexture")){
                int tex=p.getAsJsonObject("baseColorTexture").get("index").getAsInt();
                texture="image_"+array(json,"textures").get(tex).getAsJsonObject().get("source").getAsInt();
            }
            String emission=null;if(m.has("emissiveTexture")){int tex=m.getAsJsonObject("emissiveTexture").get("index").getAsInt();emission="image_"+array(json,"textures").get(tex).getAsJsonObject().get("source").getAsInt();}
            float[] ec=floats(m,"emissiveFactor",new float[]{0,0,0});
            materials.add(new Material(c[0],c[1],c[2],c[3],texture,m.has("doubleSided")&&m.get("doubleSided").getAsBoolean(),emission,ec[0],ec[1],ec[2]));
        }
        images=new ArrayList<>();for(var raw:array(json,"images")){
            var im=raw.getAsJsonObject();
            if(im.has("bufferView")){var view=array(json,"bufferViews").get(im.get("bufferView").getAsInt()).getAsJsonObject();int length=view.get("byteLength").getAsInt();byte[] data=new byte[length];var b=buffers[integer(view,"buffer",0)].duplicate();b.position(integer(view,"byteOffset",0));b.get(data);images.add(data);}
            else if(im.has("uri")&&im.get("uri").getAsString().startsWith("data:")){String uri=im.get("uri").getAsString();images.add(Base64.getDecoder().decode(uri.substring(uri.indexOf(',')+1)));}
            else images.add(null);
        }
        primitives=new ArrayList<>();var meshes=array(json,"meshes");
        for(int node=0;node<nodes.length;node++){
            var n=ns.get(node).getAsJsonObject();if(!n.has("mesh"))continue;
            for(var raw:meshes.get(n.get("mesh").getAsInt()).getAsJsonObject().getAsJsonArray("primitives")){
                var p=raw.getAsJsonObject();if(integer(p,"mode",4)!=4)throw new IOException("Only glTF TRIANGLES supported");
                var a=p.getAsJsonObject("attributes");float[] pos=accessor(a.get("POSITION").getAsInt());int count=pos.length/3;
                float[] normal=a.has("NORMAL")?accessor(a.get("NORMAL").getAsInt()):null;
                float[] uv=a.has("TEXCOORD_0")?accessor(a.get("TEXCOORD_0").getAsInt()):new float[count*2];
                int[] joints=a.has("JOINTS_0")?toInts(accessor(a.get("JOINTS_0").getAsInt())):null;
                float[] weights=a.has("WEIGHTS_0")?accessor(a.get("WEIGHTS_0").getAsInt()):null;
                int[] indices;if(p.has("indices"))indices=toInts(accessor(p.get("indices").getAsInt()));else{indices=new int[count];for(int i=0;i<count;i++)indices[i]=i;}
                for(int index:indices)if(index<0||index>=count)throw new IOException("triangle index outside vertex buffer");
                if(normal==null)normal=buildNormals(pos,indices);
                List<CubeRange> ranges=new ArrayList<>();if(p.has("extras"))for(var rawRange:array(p.getAsJsonObject("extras"),"cube_ranges")){var range=rawRange.getAsJsonObject();ranges.add(new CubeRange(integer(range,"first_index",0),integer(range,"index_count",0),range.get("diagonal_m").getAsFloat(),string(range,"author_uuid",""),range.has("emissive")&&range.get("emissive").getAsBoolean(),string(range,"lod_hint","")));}
                primitives.add(new Primitive(pos,normal,uv,joints,weights,indices,integer(p,"material",-1),node,integer(n,"skin",-1),ranges));
            }
        }
        clips=new LinkedHashMap<>();
        for(var raw:array(json,"animations")){
            var anim=raw.getAsJsonObject();var samplers=anim.getAsJsonArray("samplers");List<Channel> channels=new ArrayList<>();float duration=0;
            for(var cr:anim.getAsJsonArray("channels")){
                var c=cr.getAsJsonObject();var sampler=samplers.get(c.get("sampler").getAsInt()).getAsJsonObject();var target=c.getAsJsonObject("target");
                String path=target.get("path").getAsString();if(path.equals("weights"))continue;
                float[] time=accessor(sampler.get("input").getAsInt()),values=accessor(sampler.get("output").getAsInt());
                if(time.length>0)duration=java.lang.Math.max(duration,time[time.length-1]);int width=path.equals("rotation")?4:3;
                channels.add(new Channel(target.get("node").getAsInt(),path,string(sampler,"interpolation","LINEAR"),time,values,width));
            }
            String name=string(anim,"name","animation_"+clips.size());clips.put(name,new Clip(name,duration,channels));
        }
    }
    public static GltfSkin load(byte[] bytes) throws IOException {
        ByteBuffer glb=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);JsonObject json;byte[] binary=null;
        if(bytes.length>=12&&glb.getInt(0)==0x46546c67){
            if(glb.getInt(4)!=2||glb.getInt(8)!=bytes.length)throw new IOException("invalid GLB 2.0 header");glb.position(12);json=null;
            while(glb.remaining()>=8){int length=glb.getInt(),type=glb.getInt();if(length<0||length>glb.remaining())throw new IOException("GLB chunk overflow");byte[] chunk=new byte[length];glb.get(chunk);if(type==0x4e4f534a)json=JsonParser.parseString(new String(chunk,java.nio.charset.StandardCharsets.UTF_8).trim()).getAsJsonObject();else if(type==0x004e4942)binary=chunk;}
            if(json==null)throw new IOException("GLB missing JSON");
        }else json=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        var bs=array(json,"buffers");ByteBuffer[] buffers=new ByteBuffer[bs.size()];
        for(int i=0;i<buffers.length;i++){
            var b=bs.get(i).getAsJsonObject();byte[] data=binary;
            if(b.has("uri")){String uri=b.get("uri").getAsString();if(!uri.startsWith("data:"))throw new IOException("External buffers must be embedded for probe");data=Base64.getDecoder().decode(uri.substring(uri.indexOf(',')+1));}
            if(data==null)throw new IOException("missing buffer");buffers[i]=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }
        return new GltfSkin(json,buffers);
    }
    private float[] accessor(int index) throws IOException {
        var a=array(json,"accessors").get(index).getAsJsonObject();int count=a.get("count").getAsInt(),components=switch(a.get("type").getAsString()){case "SCALAR"->1;case "VEC2"->2;case "VEC3"->3;case "VEC4"->4;case "MAT4"->16;default->throw new IOException("accessor type");};
        if(a.has("sparse"))throw new IOException("Sparse accessor must be densified by exporter");float[] out=new float[count*components];if(!a.has("bufferView"))return out;
        int type=a.get("componentType").getAsInt(),size=switch(type){case 5120,5121->1;case 5122,5123->2;case 5125,5126->4;default->throw new IOException("component type");};
        var view=array(json,"bufferViews").get(a.get("bufferView").getAsInt()).getAsJsonObject();var b=buffers[integer(view,"buffer",0)];int offset=integer(view,"byteOffset",0)+integer(a,"byteOffset",0),stride=integer(view,"byteStride",size*components);boolean normalized=a.has("normalized")&&a.get("normalized").getAsBoolean();
        for(int n=0;n<count;n++)for(int c=0;c<components;c++){
            int pos=offset+n*stride+c*size;float v=switch(type){case 5120->b.get(pos);case 5121->Byte.toUnsignedInt(b.get(pos));case 5122->b.getShort(pos);case 5123->Short.toUnsignedInt(b.getShort(pos));case 5125->Integer.toUnsignedLong(b.getInt(pos));case 5126->b.getFloat(pos);default->0;};
            if(normalized)v=switch(type){case 5120->java.lang.Math.max(-1,v/127);case 5121->v/255;case 5122->java.lang.Math.max(-1,v/32767);case 5123->v/65535;default->v;};out[n*components+c]=v;
        }return out;
    }
    public Pose createPose(){return new Pose(this);}
    public static final class Pose {
        private final GltfSkin model;private final Vector3f[] t,s;private final Quaternionf[] r;public final Matrix4f[] world;
        public final Matrix4f[][] joints;public final Matrix3f[][] normals;private final boolean[] computed;private final float[] sample=new float[4];
        Pose(GltfSkin model){this.model=model;int n=model.nodes.length;t=new Vector3f[n];s=new Vector3f[n];r=new Quaternionf[n];world=new Matrix4f[n];computed=new boolean[n];for(int i=0;i<n;i++){t[i]=new Vector3f();s[i]=new Vector3f();r[i]=new Quaternionf();world[i]=new Matrix4f();}joints=new Matrix4f[model.skins.length][];normals=new Matrix3f[model.skins.length][];for(int i=0;i<joints.length;i++){joints[i]=new Matrix4f[model.skins[i].joints.length];normals[i]=new Matrix3f[joints[i].length];for(int j=0;j<joints[i].length;j++){joints[i][j]=new Matrix4f();normals[i][j]=new Matrix3f();}}}
        public void resetLocal(){
            for(int i=0;i<t.length;i++){t[i].set(model.nodes[i].translation);r[i].set(model.nodes[i].rotation);s[i].set(model.nodes[i].scale);computed[i]=false;}
        }
        public void setLocal(int node,Vector3f translation,Quaternionf rotation,Vector3f scale){t[node].set(translation);r[node].set(rotation).normalize();s[node].set(scale);Arrays.fill(computed,false);}
        public void sample(String name,double seconds,boolean loop){
            resetLocal();
            Clip clip=model.clips.get(name);if(clip!=null){float time=(float)(loop&&clip.duration>0?seconds%clip.duration:java.lang.Math.min(seconds,clip.duration));if(time<0)time+=clip.duration;
                for(Channel c:clip.channels){GltfSkin.sample(c,time,sample);switch(c.path){case "translation"->t[c.node].set(sample[0],sample[1],sample[2]);case "scale"->s[c.node].set(sample[0],sample[1],sample[2]);case "rotation"->r[c.node].set(sample[0],sample[1],sample[2],sample[3]).normalize();}}
            }
            finish();
        }
        public void finish(){
            for(int i=0;i<t.length;i++)compute(i,0);
            for(int k=0;k<joints.length;k++)for(int j=0;j<joints[k].length;j++){joints[k][j].set(world[model.skins[k].joints[j]]).mul(model.skins[k].inverseBind[j]);joints[k][j].normal(normals[k][j]);}
        }
        private void compute(int n,int depth){if(computed[n])return;if(depth>model.nodes.length)throw new IllegalStateException("cyclic node tree");Node node=model.nodes[n];world[n].translationRotateScale(t[n],r[n],s[n]);if(node.matrix!=null)world[n].set(node.matrix);if(node.parent>=0){compute(node.parent,depth+1);world[n].mulLocal(world[node.parent]);}computed[n]=true;}
    }
    private static void sample(Channel c,float time,float[] dest){
        int len=c.time.length;if(len==0)return;int found=Arrays.binarySearch(c.time,time);int upper=found>=0?found:-found-1;upper=java.lang.Math.min(len-1,upper);int lower=found>=0?upper:java.lang.Math.max(0,upper-1);if(time>=c.time[len-1])lower=upper=len-1;
        int width=c.width;boolean cubic=c.interpolation.equals("CUBICSPLINE");int factor=cubic?3:1;int lo=lower*width*factor+(cubic?width:0),hi=upper*width*factor+(cubic?width:0);
        float dt=c.time[upper]-c.time[lower],u=dt<=0?0:java.lang.Math.clamp((time-c.time[lower])/dt,0,1);
        if(c.interpolation.equals("STEP"))u=0;
        if(cubic){float u2=u*u,u3=u2*u;for(int k=0;k<width;k++)dest[k]=(2*u3-3*u2+1)*c.values[lo+k]+(u3-2*u2+u)*dt*c.values[lo+width+k]+(-2*u3+3*u2)*c.values[hi+k]+(u3-u2)*dt*c.values[hi-width+k];if(width==4)normalizeQuat(dest);}
        else if(width==4){
            float dot=0;for(int k=0;k<4;k++)dot+=c.values[lo+k]*c.values[hi+k];float sign=dot<0?-1:1;dot=java.lang.Math.abs(dot);float a=1-u,b=u;
            if(dot<.9995){double theta=java.lang.Math.acos(java.lang.Math.clamp(dot,-1,1)),sin=java.lang.Math.sin(theta);a=(float)(java.lang.Math.sin((1-u)*theta)/sin);b=(float)(java.lang.Math.sin(u*theta)/sin);}for(int k=0;k<4;k++)dest[k]=c.values[lo+k]*a+c.values[hi+k]*b*sign;normalizeQuat(dest);
        }else for(int k=0;k<width;k++)dest[k]=c.values[lo+k]*(1-u)+c.values[hi+k]*u;
    }
    private static void normalizeQuat(float[] q){float m=(float)java.lang.Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);if(m==0){q[3]=1;return;}for(int i=0;i<4;i++)q[i]/=m;}
    private static float[] buildNormals(float[] p,int[] indices){float[] normals=new float[p.length];for(int i=0;i+2<indices.length;i+=3){int a=indices[i]*3,b=indices[i+1]*3,c=indices[i+2]*3;float ux=p[b]-p[a],uy=p[b+1]-p[a+1],uz=p[b+2]-p[a+2],vx=p[c]-p[a],vy=p[c+1]-p[a+1],vz=p[c+2]-p[a+2];float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;for(int n:new int[]{a,b,c}){normals[n]+=nx;normals[n+1]+=ny;normals[n+2]+=nz;}}for(int i=0;i<normals.length;i+=3){float len=(float)java.lang.Math.sqrt(normals[i]*normals[i]+normals[i+1]*normals[i+1]+normals[i+2]*normals[i+2]);if(len>0){normals[i]/=len;normals[i+1]/=len;normals[i+2]/=len;}}return normals;}
    private static JsonArray array(JsonObject o,String key){return o.has(key)?o.getAsJsonArray(key):new JsonArray();}
    private static String string(JsonObject o,String k,String d){return o.has(k)?o.get(k).getAsString():d;}
    private static int integer(JsonObject o,String k,int d){return o.has(k)?o.get(k).getAsInt():d;}
    private static int[] ints(JsonArray a){int[] v=new int[a.size()];for(int i=0;i<v.length;i++)v[i]=a.get(i).getAsInt();return v;}
    private static int[] toInts(float[] a){int[] v=new int[a.length];for(int i=0;i<v.length;i++)v[i]=(int)a[i];return v;}
    private static float[] floats(JsonObject o,String k,float[] d){if(!o.has(k))return d;JsonArray a=o.getAsJsonArray(k);float[] v=new float[a.size()];for(int i=0;i<v.length;i++)v[i]=a.get(i).getAsFloat();return v;}
}

