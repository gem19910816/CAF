package com.caf.btmesh;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix4f;
import org.joml.Matrix3f;

/** CPU LBS at indexed source vertices; cached arrays and matrices, no per-vertex allocations. */
public final class SkinnedMeshRenderer implements AutoCloseable {
    public final GltfSkin model;public final GltfSkin.Pose pose;
    public float detailDistance;
    /** Driver-only cockpit near-plane cutaway. Model-local metres; unset for
     * all external views. Never changes geometry or the approved BB animation. */
    public org.joml.Vector3f cockpitCamera;
    /** Ordered near-plane corners in the same model-local metres as skinned positions. */
    public org.joml.Vector3f[] cockpitNearPlane;
    public Set<String> cockpitCutaway=Set.of();
    private final List<float[]> skinnedPositions=new ArrayList<>(),skinnedNormals=new ArrayList<>();
    private final List<ResourceLocation> textures=new ArrayList<>();
    private final List<NativeImage> decodedImages=new ArrayList<>();
    private record RenderPlan(int[] indices,int[] emissiveIndices,int[] vertices,int[] quads,int[] emissiveQuads){}
    private final List<RenderPlan[]> plans=new ArrayList<>();
    private final ResourceLocation white;
    public SkinnedMeshRenderer(GltfSkin model){
        this.model=model;pose=model.createPose();NativeImage blank=new NativeImage(1,1,false);blank.setPixelRGBA(0,0,0xFFFFFFFF);white=Minecraft.getInstance().getTextureManager().register("btmesh_white",new DynamicTexture(blank));
        for(var p:model.primitives){skinnedPositions.add(new float[p.positions().length]);skinnedNormals.add(new float[p.normals().length]);}
        for(int i=0;i<model.images.size();i++){
            byte[] image=model.images.get(i);if(image==null){textures.add(white);decodedImages.add(null);continue;}
            try{NativeImage nativeImage=NativeImage.read(new java.io.ByteArrayInputStream(image));decodedImages.add(nativeImage);DynamicTexture texture=new DynamicTexture(nativeImage);textures.add(Minecraft.getInstance().getTextureManager().register("btmesh_native_"+i,texture));}
            catch(Exception error){throw new IllegalArgumentException("Image "+i+" decode failed",error);}
        }
        for(var primitive:model.primitives)plans.add(new RenderPlan[]{plan(primitive,0),plan(primitive,.12F),plan(primitive,.25F)});
    }
    public void render(PoseStack stack,MultiBufferSource buffers,int light,String animation,double seconds,boolean loop){
        pose.sample(animation,seconds,loop);
        renderPose(stack,buffers,light);
    }
    public List<Map<String,Object>> budgets(){
        List<Map<String,Object>> result=new ArrayList<>();
        for(int lod=0;lod<3;lod++){int vertices=0,triangles=0,glowTriangles=0,emitted=0;for(RenderPlan[] levels:plans){RenderPlan p=levels[lod];vertices+=p.vertices.length;triangles+=p.indices.length/3;glowTriangles+=p.emissiveIndices.length/3;emitted+=p.quads.length>0?p.quads.length:p.indices.length/3*4;emitted+=p.emissiveQuads.length>0?p.emissiveQuads.length:p.emissiveIndices.length/3*4;}result.add(Map.of("distance_min_metres",lod==0?0:lod==1?32:64,"skinned_vertices",vertices,"visible_triangles",triangles,"emissive_triangles",glowTriangles,"emitted_quad_format_vertices",emitted));}
        return result;
    }
    public void renderPose(PoseStack stack,MultiBufferSource buffers,int light){
        long renderStart=System.nanoTime(),cpuStart=NativeRenderProfiler.cpu();
        PoseStack.Pose transform=stack.last();
        int lod=detailDistance>64?2:detailDistance>32?1:0;
        for(int pi=0;pi<model.primitives.size();pi++){
            var p=model.primitives.get(pi);RenderPlan plan=plans.get(pi)[lod];if(plan.indices.length==0)continue;
            float[] dst=skinnedPositions.get(pi),normal=skinnedNormals.get(pi);skin(p,dst,normal,plan.vertices);
            RenderPlan drawPlan=plan;
            if(cockpitCamera!=null&&!cockpitCutaway.isEmpty()&&!p.cubeRanges().isEmpty())drawPlan=cutaway(p,plan,dst);
            var material=p.material()>=0?model.materials.get(p.material()):new GltfSkin.Material(1,1,1,1,null,true,null,0,0,0);
            ResourceLocation texture=white;if(material.texture()!=null){int image=Integer.parseInt(material.texture().substring(6));if(image<textures.size())texture=textures.get(image);}
            RenderType type=material.a()<.999F?RenderType.entityTranslucent(texture):material.doubleSided()?RenderType.entityCutoutNoCull(texture):RenderType.entityCutout(texture);
            VertexConsumer vertices=buffers.getBuffer(type);int[] indices=drawPlan.indices;
            int red=java.lang.Math.round(material.r()*255),green=java.lang.Math.round(material.g()*255),blue=java.lang.Math.round(material.b()*255),alpha=java.lang.Math.round(material.a()*255);
            emitStream(vertices,transform,p,dst,normal,indices,drawPlan.quads,red,green,blue,alpha,light);
            if(material.emissiveTexture()!=null&&drawPlan.emissiveIndices.length>0){
                int ei=Integer.parseInt(material.emissiveTexture().substring(6));ResourceLocation glow=textures.get(ei);VertexConsumer emission=buffers.getBuffer(RenderType.eyes(glow));
                int er=Math.round(material.er()*255),eg=Math.round(material.eg()*255),eb=Math.round(material.eb()*255);
                emitStream(emission,transform,p,dst,normal,drawPlan.emissiveIndices,drawPlan.emissiveQuads,er,eg,eb,255,15728880);
            }
        }
        NativeRenderProfiler.record("all_mesh_skin_emit",renderStart,cpuStart);
    }
    private RenderPlan cutaway(GltfSkin.Primitive primitive,RenderPlan source,float[] points){
        Set<Integer> hidden=new HashSet<>();
        for(var cube:primitive.cubeRanges()){
            if(!cockpitCutaway.contains(cube.authorUuid()))continue;
            if(!cameraIntersectsCube(primitive,points,cube.firstIndex(),cube.count()))continue;
            for(int i=cube.firstIndex();i<cube.firstIndex()+cube.count();i++)hidden.add(primitive.indices()[i]);
        }
        if(hidden.isEmpty())return source;
        int[] opaque=Arrays.stream(source.indices).filter(v->!hidden.contains(v)).toArray(),glow=Arrays.stream(source.emissiveIndices).filter(v->!hidden.contains(v)).toArray();
        return new RenderPlan(opaque,glow,source.vertices,cubeQuads(primitive,opaque),cubeQuads(primitive,glow));
    }
    /** Actual convex cube planes, so a rotated cube never inherits its oversized AABB.
     * Clip the near rectangle against every inward halfspace. Corner-only tests miss
     * narrow edges passing through the rectangle without containing any corner. */
    private boolean cameraIntersectsCube(GltfSkin.Primitive primitive,float[] points,int first,int count){
        var center=new org.joml.Vector3f();
        for(int i=first;i<first+count;i++){int v=primitive.indices()[i]*3;center.add(points[v],points[v+1],points[v+2]);}
        center.div(count);
        List<org.joml.Vector3f> polygon=new ArrayList<>(8);
        if(cockpitNearPlane!=null)for(var p:cockpitNearPlane)polygon.add(new org.joml.Vector3f(p));
        boolean cameraInside=true;int planes=0;float epsilon=.0075F;
        for(int i=first;i+2<first+count;i+=3){
            int ia=primitive.indices()[i]*3,ib=primitive.indices()[i+1]*3,ic=primitive.indices()[i+2]*3;
            var a=new org.joml.Vector3f(points[ia],points[ia+1],points[ia+2]);
            var normal=new org.joml.Vector3f(points[ib],points[ib+1],points[ib+2]).sub(a)
                .cross(new org.joml.Vector3f(points[ic],points[ic+1],points[ic+2]).sub(a));
            if(normal.lengthSquared()<1e-14F)continue;
            normal.normalize();if(normal.dot(new org.joml.Vector3f(center).sub(a))>0)normal.negate();
            float d=normal.dot(a)+epsilon;planes++;
            if(normal.dot(cockpitCamera)>d)cameraInside=false;
            if(!polygon.isEmpty()){
                List<org.joml.Vector3f> clipped=new ArrayList<>(polygon.size()+2);
                var previous=polygon.getLast();float before=normal.dot(previous)-d;
                for(var current:polygon){
                    float after=normal.dot(current)-d;
                    if((before<=0)!=(after<=0))clipped.add(new org.joml.Vector3f(previous).lerp(current,before/(before-after)));
                    if(after<=0)clipped.add(current);
                    previous=current;before=after;
                }
                polygon=clipped;
            }
            if(!cameraInside&&polygon.isEmpty())return false;
        }
        return planes>=4&&(cameraInside||!polygon.isEmpty());
    }
    /** LOD/range membership and luminous UV regions are resolved once at load.
     * Both passes reuse the plan; hidden cubes never reach the skinning loop. */
    private RenderPlan plan(GltfSkin.Primitive p,float cutoff){
        boolean[] visible=new boolean[p.indices().length];Arrays.fill(visible,true);
        for(var range:p.cubeRanges())if(!range.emissive()&&!range.lodHint().equals("silhouette")&&range.diagonal()<cutoff)Arrays.fill(visible,range.firstIndex(),range.firstIndex()+range.count(),false);
        int[] indices=new int[p.indices().length],glow=new int[p.indices().length];int n=0,g=0;
        boolean[] used=new boolean[p.positions().length/3];NativeImage emission=null;
        if(p.material()>=0){var material=model.materials.get(p.material());if(material.emissiveTexture()!=null&&(material.er()>0||material.eg()>0||material.eb()>0))emission=decodedImages.get(Integer.parseInt(material.emissiveTexture().substring(6)));}
        int faceSize=!p.cubeRanges().isEmpty()&&p.indices().length%6==0?6:3;
        for(int i=0;i+faceSize-1<p.indices().length;i+=faceSize){if(!visible[i])continue;for(int k=0;k<faceSize;k++){int vertex=p.indices()[i+k];indices[n++]=vertex;used[vertex]=true;}
            if(emission!=null&&(hasEmission(p,i,emission)||(faceSize==6&&hasEmission(p,i+3,emission))))for(int k=0;k<faceSize;k++)glow[g++]=p.indices()[i+k];}
        int count=0;for(boolean value:used)if(value)count++;int[] vertices=new int[count];int at=0;for(int v=0;v<used.length;v++)if(used[v])vertices[at++]=v;
        indices=Arrays.copyOf(indices,n);glow=Arrays.copyOf(glow,g);
        return new RenderPlan(indices,glow,vertices,cubeQuads(p,indices),cubeQuads(p,glow));
    }
    /** Pure-cube faces are two original coplanar triangles. Rebuild their
     * verified directed perimeter to emit one MC quad, preserving winding/UV.
     * Unknown research triangles retain the degenerate-quad fallback. */
    private static int[] cubeQuads(GltfSkin.Primitive p,int[] indices){
        if(p.cubeRanges().isEmpty()||indices.length%6!=0)return new int[0];int[] result=new int[indices.length/6*4];
        for(int i=0;i<indices.length;i+=6){int a=indices[i],b=indices[i+1],c=indices[i+2],d=indices[i+3],e=indices[i+4],f=indices[i+5];int[][] edges={{a,b},{b,c},{c,a},{d,e},{e,f},{f,d}};boolean[] internal=new boolean[6];
            for(int x=0;x<6;x++)for(int y=x+1;y<6;y++)if(edges[x][0]==edges[y][1]&&edges[x][1]==edges[y][0])internal[x]=internal[y]=true;
            int boundary=0;for(boolean value:internal)if(!value)boundary++;if(boundary!=4)return new int[0];
            int current=a,at=i/6*4;for(int k=0;k<4;k++){result[at+k]=current;int next=-1;for(int j=0;j<6;j++)if(!internal[j]&&edges[j][0]==current){if(next!=-1)return new int[0];next=edges[j][1];}if(next<0)return new int[0];current=next;}
            if(current!=a||result[at]==result[at+2]||result[at+1]==result[at+3])return new int[0];
        }return result;
    }
    private static void emitStream(VertexConsumer out,PoseStack.Pose transform,GltfSkin.Primitive p,float[] positions,float[] normals,int[] triangles,int[] quads,int r,int g,int b,int a,int light){
        if(quads.length>0){for(int index:quads)emit(out,transform,p,positions,normals,index,r,g,b,a,light);return;}
        for(int i=0;i+2<triangles.length;i+=3){emit(out,transform,p,positions,normals,triangles[i],r,g,b,a,light);emit(out,transform,p,positions,normals,triangles[i+1],r,g,b,a,light);emit(out,transform,p,positions,normals,triangles[i+2],r,g,b,a,light);emit(out,transform,p,positions,normals,triangles[i+2],r,g,b,a,light);}
    }
    private static boolean hasEmission(GltfSkin.Primitive p,int first,NativeImage image){
        float minU=1,minV=1,maxU=0,maxV=0;for(int k=0;k<3;k++){int v=p.indices()[first+k]*2;minU=Math.min(minU,p.uv()[v]);maxU=Math.max(maxU,p.uv()[v]);minV=Math.min(minV,p.uv()[v+1]);maxV=Math.max(maxV,p.uv()[v+1]);}
        int x0=Math.max(0,(int)Math.floor(minU*image.getWidth())),x1=Math.min(image.getWidth()-1,(int)Math.floor(maxU*image.getWidth()));
        int y0=Math.max(0,(int)Math.floor(minV*image.getHeight())),y1=Math.min(image.getHeight()-1,(int)Math.floor(maxV*image.getHeight()));
        for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){int rgba=image.getPixelRGBA(x,y);if((rgba>>>24)!=0&&(rgba&0xFFFFFF)!=0)return true;}return false;
    }
    private void skin(GltfSkin.Primitive p,float[] dst,float[] norm,int[] activeVertices){
        float[] pos=p.positions(),srcN=p.normals();boolean weighted=p.skin()>=0&&p.joints()!=null&&p.weights()!=null;
        Matrix4f[] joints=weighted?pose.joints[p.skin()]:null;Matrix3f[] normalJoints=weighted?pose.normals[p.skin()]:null;
        Matrix4f world=pose.world[p.node()];Matrix3f nodeNormal=new Matrix3f();world.normal(nodeNormal);
        for(int v:activeVertices){
            int b=v*3;float x=pos[b],y=pos[b+1],z=pos[b+2],nx=srcN[b],ny=srcN[b+1],nz=srcN[b+2];
            float px=0,py=0,pz=0,ox=0,oy=0,oz=0,total=0;
            for(int k=0;k<(weighted?4:1);k++){
                float weight=weighted?p.weights()[v*4+k]:1;if(weight==0)continue;
                int joint=weighted?p.joints()[v*4+k]:0;if(weighted&&(joint<0||joint>=joints.length))throw new IllegalArgumentException("joint index outside skin");
                Matrix4f m=weighted?joints[joint]:world;Matrix3f n=weighted?normalJoints[joint]:nodeNormal;
                px+=(m.m00()*x+m.m10()*y+m.m20()*z+m.m30())*weight;
                py+=(m.m01()*x+m.m11()*y+m.m21()*z+m.m31())*weight;
                pz+=(m.m02()*x+m.m12()*y+m.m22()*z+m.m32())*weight;
                ox+=(n.m00()*nx+n.m10()*ny+n.m20()*nz)*weight;
                oy+=(n.m01()*nx+n.m11()*ny+n.m21()*nz)*weight;
                oz+=(n.m02()*nx+n.m12()*ny+n.m22()*nz)*weight;total+=weight;
            }
            if(total>0){px/=total;py/=total;pz/=total;}
            dst[b]=px;dst[b+1]=py;dst[b+2]=pz;float len=(float)java.lang.Math.sqrt(ox*ox+oy*oy+oz*oz);
            norm[b]=len>1e-10F?ox/len:0;norm[b+1]=len>1e-10F?oy/len:1;norm[b+2]=len>1e-10F?oz/len:0;
        }
    }
    private static void emit(VertexConsumer out,PoseStack.Pose transform,GltfSkin.Primitive p,float[] pos,float[] n,int index,int r,int g,int b,int a,int light){
        int i=index*3,uv=index*2;
        out.addVertex(transform.pose(),pos[i],pos[i+1],pos[i+2]).setColor(r,g,b,a).setUv(p.uv()[uv],p.uv()[uv+1])
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(transform,n[i],n[i+1],n[i+2]);
    }
    @Override public void close(){var manager=Minecraft.getInstance().getTextureManager();for(var t:textures)if(!t.equals(white))manager.release(t);manager.release(white);}
}

