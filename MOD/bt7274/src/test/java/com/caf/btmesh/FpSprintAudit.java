package com.caf.btmesh;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Inspects the actual exported sprint, including camera-relative motion. */
public final class FpSprintAudit {
    public static void main(String[] args)throws Exception {
        Path root=Path.of("src/main/resources/assets/bt7274");
        var model=GltfSkin.load(Files.readAllBytes(root.resolve("models/native/fp.glb")));
        var metadata=JsonParser.parseString(Files.readString(root.resolve("motion/fp.json"))).getAsJsonObject();
        var motion=new NativeMotionPlayer(model,metadata);var pose=model.createPose();
        double duration=motion.sequenceDuration("sprint_seq",Map.of());
        var cam=new Matrix4f();var rel=new Matrix4f();
        if(Math.abs(duration-25/30.0)>1e-6)throw new AssertionError("original sprint duration changed");
        Vector3f min=new Vector3f(Float.POSITIVE_INFINITY),max=new Vector3f(Float.NEGATIVE_INFINITY);
        Matrix4f first=new Matrix4f();
        for(int i=0;i<=8;i++) {
            double t=i*duration/8;motion.sampleFirstPerson(NativeMotionPlayer.FirstPersonState.SPRINT,t,0,0,100,pose);
            motion.attachment(pose,"CAMERA",cam);
            for(float value:cam.get(new float[16]))if(!Float.isFinite(value))throw new AssertionError("non-finite camera");
            for(String name:List.of("muzzle_flash","muzzle","weapon_bone"))if(motion.attachment(pose,name,rel)) {
                rel.set(cam).invert().mul(attachment(motion,pose,name));
                Vector3f point=rel.getTranslation(new Vector3f());min.min(point);max.max(point);
                if(i==0)first.set(rel);
                if(i==8&&!first.equals(rel,1e-3f))throw new AssertionError("sprint loop endpoint jumps");
            }
        }
        float travel=max.distance(min);
        if(travel<10)throw new AssertionError("actual original sprint relative weapon motion absent: "+travel);
        motion.sampleFirstPerson(NativeMotionPlayer.FirstPersonState.SPRINT,.35,0,0,100,pose);
        var frozen=Arrays.stream(pose.world).map(Matrix4f::new).toArray(Matrix4f[]::new);
        for(int i=0;i<20;i++)motion.sampleFirstPerson(NativeMotionPlayer.FirstPersonState.SPRINT,.35,0,0,100,pose);
        for(int i=0;i<frozen.length;i++)if(!frozen[i].equals(pose.world[i],0))throw new AssertionError("render sampling accumulates sprint");
        System.out.println("PASS actual original sprint duration, loop, finite camera, nonzero relative motion, frame-idempotent evaluation; travel="+travel+" source inches");
    }
    private static Matrix4f attachment(NativeMotionPlayer m,GltfSkin.Pose p,String n){var r=new Matrix4f();m.attachment(p,n,r);return r;}
}
