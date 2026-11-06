package com.caf.btmesh;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Final cube artifact audit, separate from the retained source-mesh research audit. */
public final class CubeNumericalAudit {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path resources=Path.of("src/main/resources/assets/bt7274"),models=resources.resolve("models/native");
        Path provenancePath=models.resolve("cube-provenance.json");
        check(Files.isRegularFile(provenancePath),"final cube provenance exists");
        JsonObject provenance=JsonParser.parseString(Files.readString(provenancePath)).getAsJsonObject();
        check(provenance.get("authoring_mesh_count").getAsInt()==0,"authoring contains no mesh elements");
        check(provenance.get("authoring_cube_count").getAsInt()>0,"authoring contains cubes");
        List<Map<String,Object>> reports=new ArrayList<>();
        for(String name:List.of("body.glb","fp.glb")){
            byte[] bytes=Files.readAllBytes(models.resolve(name));
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            check(hash.equals(provenance.getAsJsonObject("assets").getAsJsonObject(name).get("sha256").getAsString()),"final asset hash "+name);
            GltfSkin model=GltfSkin.load(bytes);int vertices=0,triangles=0,cubeRanges=0;Set<String> authors=new HashSet<>();
            check(!model.primitives.isEmpty(),"renderable geometry "+name);
            for(var primitive:model.primitives){
                int count=primitive.positions().length/3;vertices+=count;triangles+=primitive.indices().length/3;
                check(primitive.skin()>=0&&primitive.joints()!=null&&primitive.weights()!=null,"rigid skeletal binding "+name);
                check(!primitive.cubeRanges().isEmpty(),"geometry carries author cube ranges "+name);
                var rebuild=SkinnedMeshRenderer.class.getDeclaredMethod("cubeQuads",GltfSkin.Primitive.class,int[].class);rebuild.setAccessible(true);
                int[] quads=(int[])rebuild.invoke(null,primitive,primitive.indices());
                check(quads.length==primitive.indices().length/6*4,"native MC quad rebuild halves pure-cube emission vertices");
                for(int face=0;face<quads.length/4;face++){
                    Set<Integer> before=new HashSet<>(),after=new HashSet<>();for(int k=0;k<6;k++)before.add(primitive.indices()[face*6+k]);for(int k=0;k<4;k++)after.add(quads[face*4+k]);
                    check(before.equals(after)&&before.size()==4,"quad rebuild preserves every authored UV corner");
                    var originalNormal=normal(primitive,primitive.indices()[face*6],primitive.indices()[face*6+1],primitive.indices()[face*6+2]);
                    check(originalNormal.dot(normal(primitive,quads[face*4],quads[face*4+1],quads[face*4+2]))>0,"quad rebuild preserves outward winding");
                }
                boolean[] covered=new boolean[primitive.indices().length];
                for(var range:primitive.cubeRanges()){
                    cubeRanges++;check(!range.authorUuid().isBlank(),"source cube UUID recorded");authors.add(range.authorUuid());
                    check(range.firstIndex()>=0&&range.count()>0&&range.count()%6==0&&range.firstIndex()+range.count()<=covered.length,"cube consists of complete quad faces");
                    for(int i=range.firstIndex();i<range.firstIndex()+range.count();i++){check(!covered[i],"nonoverlapping cube ranges");covered[i]=true;}
                }
                for(boolean item:covered)check(item,"all rendered indices have cube provenance");
                for(int v=0;v<count;v++){
                    float sum=0;int effective=0;
                    for(int k=0;k<4;k++){float w=primitive.weights()[v*4+k];sum+=w;if(w>1e-6){effective++;check(primitive.joints()[v*4+k]>=0&&primitive.joints()[v*4+k]<model.skins[primitive.skin()].joints().length,"joint index in range");}}
                    check(Math.abs(sum-1)<1e-5&&effective==1,"each cube corner is rigidly weighted");
                    for(int axis=0;axis<3;axis++)check(Float.isFinite(primitive.positions()[v*3+axis]),"finite authored vertex");
                    for(int axis=0;axis<2;axis++){float uv=primitive.uv()[v*2+axis];check(Float.isFinite(uv)&&uv>=-1e-6&&uv<=1.000001,"atlas UV in bounds");}
                }
            }
            check(vertices<=authors.size()*24,"cube vertex budget has no unexplained geometry");
            JsonObject metadata=JsonParser.parseString(Files.readString(resources.resolve("motion/"+name.replace(".glb",".json")))).getAsJsonObject();
            NativeMotionPlayer motion=new NativeMotionPlayer(model,metadata);GltfSkin.Pose pose=model.createPose();
            CubePoseAdapter adapter=metadata.has("cube_rig")?new CubePoseAdapter(model,metadata.getAsJsonObject("cube_rig")):null;
            List<String> states=name.equals("body.glb")?List.of("bt_combat_idle","bt_combat_walk_forward","bt_combat_run_forward","at_hotdrop_drop_2knee","at_hotdrop_quickstand","bt_vortex_arm_pose_add"):List.of("ads_in_seq","idle_seq","attack_seq","reload_seq","reload_empty_seq","sprint_seq","melee_seq");
            for(String state:states){check(motion.hasSequence(state),"required source sequence "+state);for(double t:new double[]{0,.05,.2,.5,1,2,4}){
                motion.sample(state,t,Map.of(),pose);if(adapter!=null)adapter.apply(pose);
                for(var matrix:pose.world)check(Float.isFinite(matrix.m30())&&Float.isFinite(matrix.m31())&&Float.isFinite(matrix.m32()),"finite final retarget "+state);
            }}
            reports.add(Map.of("asset",name,"sha256",hash,"vertices",vertices,"triangles",triangles,"author_cubes",authors.size(),"primitive_cube_ranges",cubeRanges,"required_sequences",states));
        }
        Map<String,Object> report=Map.of("status","passed","scope","Final embedded cube GLBs: lineage, rigid skin, UV and runtime source sampler; not visual acceptance or GPU timing","assertions",assertions,"assets",reports);
        Files.writeString(Path.of("F:/自制武器源文件/08_BT7274/qa/cube_final_java_audit.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("PASS final cube artifacts: "+assertions+" assertions");
    }
    private static org.joml.Vector3f normal(GltfSkin.Primitive p,int a,int b,int c){
        var origin=new org.joml.Vector3f(p.positions()[a*3],p.positions()[a*3+1],p.positions()[a*3+2]);
        return new org.joml.Vector3f(p.positions()[b*3],p.positions()[b*3+1],p.positions()[b*3+2]).sub(origin).cross(new org.joml.Vector3f(p.positions()[c*3],p.positions()[c*3+1],p.positions()[c*3+2]).sub(origin));
    }
}
