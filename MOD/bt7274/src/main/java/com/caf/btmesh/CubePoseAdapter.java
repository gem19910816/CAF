package com.caf.btmesh;

import com.google.gson.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Retargets evaluated original motion onto the authored cube joint pivots.
 * Source nodes carry no geometry. Original world angular trajectories are
 * expressed relative to a documented source reference posture; target bone
 * lengths stay authored, so no arm segments detach when proportions differ.
 */
public final class CubePoseAdapter {
    public record WeaponReleaseAnchor(Vector3f sourceOrigin,Vector3f sourceRootOrigin,Vector3f targetOrigin){}
    private record Binding(int target,int source,int parent,Quaternionf inverseReference){}
    private final GltfSkin model;
    private final Binding[] bindings;
    private record GroundChain(String side,int hip,int knee,int ankle,Vector3f[] sole){}
    private final List<GroundChain> groundChains=new ArrayList<>();
    private record EndpointConstraint(int body,int rod,int lowerAnchor,int upperAnchor,Vector3f lowerLocal,Vector3f upperLocal,Vector3f referenceAxis){}
    private final List<EndpointConstraint> endpointConstraints=new ArrayList<>();
    private int weaponNode=-1,sourceWeaponNode=-1;private int[] graspNodes=new int[0];private Vector3f gripLocal=new Vector3f();private Quaternionf weaponAxis=new Quaternionf();private float authorUnitScale=1/16f;
    private final Quaternionf wrapperRotation;
    private final Quaternionf sourceToCube=new Quaternionf(),cubeToSource=new Quaternionf();
    private final int wrapper,sourceHip,targetHip,sourceRoot;
    private final Vector3f referenceHip,referenceRoot;
    private final Quaternionf[] desired;
    private final Quaternionf sourceRotation=new Quaternionf(),local=new Quaternionf(),parentRotation=new Quaternionf();
    private final Vector3f translation=new Vector3f(),displacement=new Vector3f(),rootDisplacement=new Vector3f();
    private final Matrix4f wrapperInverse=new Matrix4f();
    private record LimbDirection(int target,int child,int source,int sourceEnd){}
    private final List<LimbDirection> limbDirections=new ArrayList<>();
    private final Map<String,Integer> nodeNames=new HashMap<>();
    private float sourceReferenceAnkleMinimum;
    private boolean sourceReferenceAnklesReady;
    private final Quaternionf sourceGunRelativeReference=new Quaternionf(),sourceTorsoToAuthorLocal=new Quaternionf();
    private boolean gunRelativeReferenceReady;

    public CubePoseAdapter(GltfSkin model,JsonObject metadata){
        this.model=model;wrapper=metadata.get("cube_wrapper_node").getAsInt();
        sourceHip=metadata.get("source_hip_node").getAsInt();targetHip=metadata.get("target_hip_node").getAsInt();
        sourceRoot=metadata.get("source_root_node").getAsInt();
        float[] r=floats(metadata.getAsJsonArray("cube_wrapper_rotation_xyzw"));wrapperRotation=new Quaternionf(r[0],r[1],r[2],r[3]);
        if(metadata.has("source_to_cube_rotation_xyzw")){float[] q=floats(metadata.getAsJsonArray("source_to_cube_rotation_xyzw"));sourceToCube.set(q[0],q[1],q[2],q[3]).normalize();cubeToSource.set(sourceToCube).invert();}
        float[] p=floats(metadata.getAsJsonArray("reference_hip_world"));referenceHip=new Vector3f(p);
        referenceRoot=new Vector3f(floats(metadata.getAsJsonArray("reference_root_world")));
        var array=metadata.getAsJsonArray("bindings");bindings=new Binding[array.size()];desired=new Quaternionf[model.nodes.length];
        for(int i=0;i<desired.length;i++)desired[i]=new Quaternionf();
        for(int i=0;i<bindings.length;i++){
            var b=array.get(i).getAsJsonObject();float[] q=floats(b.getAsJsonArray("reference_source_world_rotation_xyzw"));
            bindings[i]=new Binding(b.get("target_node").getAsInt(),b.get("source_node").getAsInt(),b.get("parent_target_node").getAsInt(),new Quaternionf(q[0],q[1],q[2],q[3]).invert());
        }
        if(metadata.has("ground_chains"))for(JsonElement raw:metadata.getAsJsonArray("ground_chains")){
            JsonObject c=raw.getAsJsonObject();JsonArray points=c.getAsJsonArray("sole_points");Vector3f[] sole=new Vector3f[points.size()];
            for(int i=0;i<sole.length;i++)sole[i]=new Vector3f(floats(points.get(i).getAsJsonArray()));
            groundChains.add(new GroundChain(c.get("side").getAsString(),c.get("hip").getAsInt(),c.get("knee").getAsInt(),c.get("ankle").getAsInt(),sole));
        }
        if(metadata.has("endpoint_constraints"))for(JsonElement raw:metadata.getAsJsonArray("endpoint_constraints")){
            JsonObject c=raw.getAsJsonObject();endpointConstraints.add(new EndpointConstraint(c.get("body_node").getAsInt(),c.get("rod_node").getAsInt(),c.get("lower_anchor_node").getAsInt(),c.get("upper_anchor_node").getAsInt(),
                new Vector3f(floats(c.getAsJsonArray("lower_anchor_local"))),new Vector3f(floats(c.getAsJsonArray("upper_anchor_local"))),new Vector3f(floats(c.getAsJsonArray("reference_axis")))));
        }
        if(metadata.has("authored_unit_scale_metres"))authorUnitScale=metadata.get("authored_unit_scale_metres").getAsFloat();
        if(metadata.has("weapon_grip")){
            var w=metadata.getAsJsonObject("weapon_grip");weaponNode=w.get("weapon_node").getAsInt();sourceWeaponNode=w.get("source_weapon_node").getAsInt();gripLocal.set(floats(w.getAsJsonArray("grip_center_local")));
            JsonArray nodes=w.getAsJsonArray(w.has("primary_grasp_nodes")?"primary_grasp_nodes":"right_grasp_nodes");graspNodes=new int[nodes.size()];for(int i=0;i<nodes.size();i++)graspNodes[i]=nodes.get(i).getAsInt();
            float[] q=floats(w.getAsJsonArray("source_weapon_axis_correction_xyzw"));weaponAxis.set(q[0],q[1],q[2],q[3]);
        }
        for(int i=0;i<model.nodes.length;i++)nodeNames.put(model.nodes[i].name,i);
        // The authored rear-knee ram halves attach to shin/thigh endpoints;
        // their original-named bones have different pivots and can flip 180
        // degrees during hotdrop. Solve our physical two-end assembly instead.
        for(String side:List.of("right","left")){
            Integer body=nodeNames.get("cube_rear_knee_piston_body_"+side),rod=nodeNames.get("cube_rear_knee_piston_rod_"+side);
            if(body!=null&&rod!=null){int lower=model.nodes[body].parent,upper=model.nodes[rod].parent;Vector3f axis=authoredRestPosition(rod).sub(authoredRestPosition(body));endpointConstraints.add(new EndpointConstraint(body,rod,lower,upper,new Vector3f(model.nodes[body].translation),new Vector3f(model.nodes[rod].translation),axis));}
        }
        // Anatomical segment directions are not the arbitrary source joint
        // coordinate axes. Preserve actual source pose, not casual-as-straight.
        for(String side:List.of("right","left")){
            String s=side.equals("right")?"r":"l";
            direction("upper_arm_"+side,"forearm_"+side,"def_"+s+"_shoulder","def_"+s+"_elbow");
            direction("forearm_"+side,"hand_"+side,"def_"+s+"_elbow","def_"+s+"_wrist");
            direction("thigh_"+side,"shin_"+side,"def_"+s+"_hip","def_"+s+"_knee");
            direction("shin_"+side,"foot_"+side,"def_"+s+"_knee","def_"+s+"_ankle");
        }
        // Reference ankle floor from the same geometry-free original casual
        // pose named in metadata; sampler operates on this private scratch pose.
        try{
            var reference=model.createPose();String clip=model.clips.keySet().stream().filter(k->k.endsWith("/"+metadata.get("reference_clip").getAsString())).findFirst().orElse(null);
            if(clip!=null){reference.sample(clip,0,false);sourceReferenceAnkleMinimum=sourceAnkleMinimum(reference);sourceReferenceAnklesReady=Float.isFinite(sourceReferenceAnkleMinimum);}
        }catch(RuntimeException ignored){sourceReferenceAnklesReady=false;}
        // A small relative gun residual preserves original fire/locomotion
        // angular kick over the Blockbench chest calibration. Source body turn
        // already lives on the target torso and must not be applied twice.
        try{String combat=model.clips.keySet().stream().filter(k->k.endsWith("/@bt_combat_idle")).findFirst().orElse(null);if(combat!=null){var refPose=model.createPose();refPose.sample(combat,0,false);int st=nodeNames.get("def_c_spineC"),tt=nodeNames.get("cube_torso");Quaternionf sq=refPose.world[st].getUnnormalizedRotation(new Quaternionf()).normalize(),gq=refPose.world[sourceWeaponNode].getUnnormalizedRotation(new Quaternionf()).normalize();sourceGunRelativeReference.set(sq).invert().mul(gq);apply(refPose);Quaternionf aq=refPose.world[tt].getUnnormalizedRotation(new Quaternionf()).normalize();sourceTorsoToAuthorLocal.set(aq).invert().mul(sourceToCube).mul(sq).normalize();gunRelativeReferenceReady=true;}}catch(RuntimeException ignored){gunRelativeReferenceReady=false;}
    }
    private void direction(String target,String child,String source,String end){
        Integer t=nodeNames.get("cube_"+target),c=nodeNames.get("cube_"+child),s=nodeNames.get(source),e=nodeNames.get(end);
        if(t!=null&&c!=null&&s!=null&&e!=null)limbDirections.add(new LimbDirection(t,c,s,e));
    }
    private Vector3f authoredRestPosition(int node){Vector3f result=new Vector3f();for(int i=node;i>=0&&i!=wrapper;i=model.nodes[i].parent)result.add(model.nodes[i].translation);return result;}
    private float sourceAnkleMinimum(GltfSkin.Pose pose){
        Integer l=nodeNames.get("def_l_ankle"),r=nodeNames.get("def_r_ankle");if(l==null||r==null)return Float.NaN;
        Vector3f root=pose.world[sourceRoot].getTranslation(new Vector3f());Vector3f left=pose.world[l].getTranslation(new Vector3f()).sub(root),right=pose.world[r].getTranslation(new Vector3f()).sub(root);sourceToCube.transform(left);sourceToCube.transform(right);return Math.min(left.y,right.y);
    }
    /** Call only for genuine grounded idle/locomotion/crouch states, before
     * small per-leg terrain IK. Preserve original lowest-ankle vertical motion
     * relative to original casual reference, compensate authored leg lengths by
     * moving pelvis/body as a whole. Never call for deployment/death/embark. */
    public void retargetGroundedPelvisHeight(GltfSkin.Pose pose){
        if(!sourceReferenceAnklesReady)return;
        float sourceMotion=sourceAnkleMinimum(pose)-sourceReferenceAnkleMinimum;
        float authoredMinimum=Math.min(soleHeight(pose,"left"),soleHeight(pose,"right"));
        if(!Float.isFinite(authoredMinimum))return;float correction=sourceMotion-authoredMinimum;
        Vector3f p=pose.world[targetHip].getTranslation(new Vector3f());p.y+=correction;Quaternionf q=pose.world[targetHip].getUnnormalizedRotation(new Quaternionf()).normalize();setWorldTransform(pose,targetHip,p,q);pose.finish();solveEndpointConstraints(pose);
    }

    /** Authored-proportion correction for an explicitly TWO-FOOT stance only.
     * The source supplies each knee plane and foot orientation; a shared pelvis
     * height makes the authored shorter legs reachable before solving. Do not
     * call for locomotion, airborne actions or a raised/swinging foot. */
    public void fitTwoFootStance(GltfSkin.Pose pose,float groundY){
        record Stance(GroundChain chain,Vector3f goal,Quaternionf foot,Vector3f pole,float upper,float lower){}
        List<Stance> stance=new ArrayList<>();float drop=0;
        for(GroundChain c:groundChains){
            Vector3f a=pose.world[c.hip].getTranslation(new Vector3f()),b=pose.world[c.knee].getTranslation(new Vector3f()),e=pose.world[c.ankle].getTranslation(new Vector3f());
            float l1=a.distance(b),l2=b.distance(e);Vector3f goal=new Vector3f(e);goal.y+=groundY-soleHeight(pose,c.side);
            float dx=goal.x-a.x,dz=goal.z-a.z,maximum=l1+l2-.03f;
            float vertical=(float)Math.sqrt(Math.max(.0001f,maximum*maximum-dx*dx-dz*dz));
            drop=Math.max(drop,a.y-goal.y-vertical);
            Vector3f axis=new Vector3f(e).sub(a).normalize(),pole=new Vector3f(b).sub(a);pole.fma(-pole.dot(axis),axis);
            // An anatomical forward reference is continuous at a nearly straight
            // source knee, where normalizing an almost-zero pole flips its sign.
            Vector3f forward=pose.world[targetHip].getUnnormalizedRotation(new Quaternionf()).normalize().transform(new Vector3f(0,0,-1));forward.fma(-forward.dot(axis),axis);
            if(forward.lengthSquared()>1e-8f){forward.normalize();float confidence=Math.min(1,pole.length()/.50f);confidence*=confidence;if(pole.lengthSquared()>1e-10f)pole.normalize();else pole.set(forward);confidence*=Math.max(0,pole.dot(forward));pole.lerp(forward,1-confidence).normalize();}
            if(pole.lengthSquared()<1e-10f)return;
            stance.add(new Stance(c,goal,pose.world[c.ankle].getUnnormalizedRotation(new Quaternionf()).normalize(),pole.normalize(),l1,l2));
        }
        if(drop>0){Vector3f p=pose.world[targetHip].getTranslation(new Vector3f());p.y-=drop;setWorldTransform(pose,targetHip,p,pose.world[targetHip].getUnnormalizedRotation(new Quaternionf()).normalize());pose.finish();}
        for(Stance s:stance){GroundChain c=s.chain;Vector3f a=pose.world[c.hip].getTranslation(new Vector3f()),b=pose.world[c.knee].getTranslation(new Vector3f()),e=pose.world[c.ankle].getTranslation(new Vector3f());
            Vector3f axis=new Vector3f(s.goal).sub(a);float distance=axis.length();if(distance>=s.upper+s.lower-.001f||distance<=Math.abs(s.upper-s.lower)+.001f)continue;axis.div(distance);
            Vector3f pole=new Vector3f(s.pole).fma(-s.pole.dot(axis),axis);if(pole.lengthSquared()<1e-10f)continue;pole.normalize();
            float along=(s.upper*s.upper-s.lower*s.lower+distance*distance)/(2*distance),height=(float)Math.sqrt(Math.max(0,s.upper*s.upper-along*along));Vector3f knee=new Vector3f(a).fma(along,axis).fma(height,pole);
            rotateSegment(pose,c.hip,new Vector3f(b).sub(a),new Vector3f(knee).sub(a));Vector3f k=pose.world[c.knee].getTranslation(new Vector3f()),f=pose.world[c.ankle].getTranslation(new Vector3f());rotateSegment(pose,c.knee,new Vector3f(f).sub(k),new Vector3f(s.goal).sub(k));setWorldRotation(pose,c.ankle,s.foot);pose.finish();
        }
        solveEndpointConstraints(pose);
    }

    /** Call after NativeMotionPlayer finishes its original source pose, before rendering. */
    public void apply(GltfSkin.Pose pose){apply(pose,true);}
    public void apply(GltfSkin.Pose pose,boolean stripAuthoritativeRootTranslation){
        // Reference values and source world matrices both include Source's export
        // wrapper, preventing axis-conversion guesses and double 90-degree turns.
        pose.world[wrapper].invert(wrapperInverse);
        pose.world[sourceHip].getTranslation(displacement).sub(referenceHip);
        if(stripAuthoritativeRootTranslation){
            pose.world[sourceRoot].getTranslation(rootDisplacement).sub(referenceRoot);
            displacement.sub(rootDisplacement);
        }
        sourceToCube.transform(displacement);wrapperInverse.transformDirection(displacement);
        for(Binding b:bindings){
            pose.world[b.source].getUnnormalizedRotation(sourceRotation).normalize();
            desired[b.target].set(sourceToCube).mul(sourceRotation).mul(b.inverseReference).mul(cubeToSource).mul(wrapperRotation).normalize();
            if(b.parent>=0)parentRotation.set(desired[b.parent]);else parentRotation.set(wrapperRotation);
            local.set(parentRotation).invert().mul(desired[b.target]).normalize();
            translation.set(model.nodes[b.target].translation);
            if(b.target==targetHip)translation.add(displacement);
            pose.setLocal(b.target,translation,local,model.nodes[b.target].scale);
        }
        pose.finish();
        preserveAnatomicalDirections(pose);
        solveEndpointConstraints(pose);
    }
    private void preserveAnatomicalDirections(GltfSkin.Pose pose){
        // Save distal world twists before moving an ancestor. Each anatomical
        // segment then gets the shortest swing to the ACTUAL source endpoints;
        // the evaluated global angular delta supplies the remaining twist.
        Quaternionf[] original=new Quaternionf[model.nodes.length];for(Binding b:bindings)original[b.target]=pose.world[b.target].getUnnormalizedRotation(new Quaternionf()).normalize();
        for(LimbDirection c:limbDirections){
            Vector3f axis=new Vector3f(model.nodes[c.child].translation);original[c.target].transform(axis);if(axis.lengthSquared()<1e-9f)continue;
            Vector3f goal=pose.world[c.sourceEnd].getTranslation(new Vector3f()).sub(pose.world[c.source].getTranslation(new Vector3f()));sourceToCube.transform(goal);if(goal.lengthSquared()<1e-9f)continue;
            Quaternionf q=new Quaternionf().rotationTo(axis.normalize(),goal.normalize()).mul(original[c.target]).normalize();setWorldRotation(pose,c.target,q);pose.finish();
        }
        // Hands/feet carry independent source orientation. Parent corrections
        // must not rotate the palm/sole twice. Fingers use actual source segment
        // directions with twist retained from the evaluated original posture.
        for(String side:List.of("right","left")){
            for(String part:List.of("hand_","foot_")){Integer node=nodeNames.get("cube_"+part+side);if(node!=null){setWorldRotation(pose,node,original[node]);pose.finish();}}
            String s=side.equals("right")?"r":"l";
            preservePalmFrame(pose,side,s,original);
            for(String finger:List.of("index","middle","ring","pinky","thumb")){
                String label=switch(finger){case "index"->"Index";case "middle"->"Mid";case "ring"->"Ring";case "pinky"->"Pinky";default->"Thumb";};
                Integer root=nodeNames.get("cube_finger_"+finger+"_"+side+"_root"),tip=nodeNames.get("cube_finger_"+finger+"_"+side+"_tip"),a=nodeNames.get("def_"+s+"_fin"+label+"A"),b=nodeNames.get("def_"+s+"_fin"+label+"B"),c=nodeNames.get("def_"+s+"_fin"+label+"C");
                if(root!=null&&tip!=null&&a!=null&&b!=null&&c!=null){
                    // The author has TWO physical segments. A->C already
                    // includes the source middle joint's bend and applying C
                    // again made a large U. Use the two actual segment axes.
                    for(int part=0;part<2;part++){int target=part==0?root:tip,start=part==0?a:b,end=part==0?b:c;Vector3f axis=new Vector3f(model.nodes[tip].translation);original[target].transform(axis);Vector3f goal=pose.world[end].getTranslation(new Vector3f()).sub(pose.world[start].getTranslation(new Vector3f()));sourceToCube.transform(goal);if(axis.lengthSquared()>1e-9f&&goal.lengthSquared()>1e-9f){Quaternionf q=new Quaternionf().rotationTo(axis.normalize(),goal.normalize()).mul(original[target]).normalize();setWorldRotation(pose,target,q);pose.finish();}}
                }
            }
        }
    }
    private void preservePalmFrame(GltfSkin.Pose pose,String side,String s,Quaternionf[] original){
        Integer hand=nodeNames.get("cube_hand_"+side),middle=nodeNames.get("cube_finger_middle_"+side+"_root"),index=nodeNames.get("cube_finger_index_"+side+"_root"),pinky=nodeNames.get("cube_finger_pinky_"+side+"_root"),w=nodeNames.get("def_"+s+"_wrist"),m=nodeNames.get("def_"+s+"_finMidA"),i=nodeNames.get("def_"+s+"_finIndexA"),p=nodeNames.get("def_"+s+"_finPinkyA");
        if(hand==null||middle==null||index==null||pinky==null||w==null||m==null||i==null||p==null)return;
        Vector3f authoredLong=new Vector3f(model.nodes[middle].translation),authoredWide=new Vector3f(model.nodes[index].translation).sub(model.nodes[pinky].translation);
        Vector3f goalLong=pose.world[m].getTranslation(new Vector3f()).sub(pose.world[w].getTranslation(new Vector3f())),goalWide=pose.world[i].getTranslation(new Vector3f()).sub(pose.world[p].getTranslation(new Vector3f()));sourceToCube.transform(goalLong);sourceToCube.transform(goalWide);
        if(authoredLong.lengthSquared()<1e-9f||goalLong.lengthSquared()<1e-9f)return;goalLong.normalize();
        Quaternionf q=new Quaternionf(original[hand]);Vector3f currentLong=q.transform(new Vector3f(authoredLong)).normalize();new Quaternionf().rotationTo(currentLong,goalLong).mul(q,q);
        Vector3f currentWide=q.transform(new Vector3f(authoredWide));currentWide.fma(-currentWide.dot(goalLong),goalLong);goalWide.fma(-goalWide.dot(goalLong),goalLong);if(currentWide.lengthSquared()<1e-9f||goalWide.lengthSquared()<1e-9f)return;currentWide.normalize();goalWide.normalize();
        float twist=(float)Math.atan2(goalLong.dot(new Vector3f(currentWide).cross(goalWide)),currentWide.dot(goalWide));new Quaternionf().rotationAxis(twist,goalLong).mul(q,q);setWorldRotation(pose,hand,q.normalize());pose.finish();
    }
    /** Additional authored hydraulics have no exact matching original bone.
     * Keep each rigid half anchored to its own moving pelvis/torso endpoint and
     * aim both on the same current line, allowing their overlap to telescope. */
    public void solveEndpointConstraints(GltfSkin.Pose pose){
        for(EndpointConstraint c:endpointConstraints){
            Vector3f lower=pose.world[c.lowerAnchor].transformPosition(c.lowerLocal,new Vector3f()),upper=pose.world[c.upperAnchor].transformPosition(c.upperLocal,new Vector3f());
            Vector3f direction=new Vector3f(upper).sub(lower);if(direction.lengthSquared()<1e-8f)continue;
            Vector3f reference=new Vector3f(c.referenceAxis);wrapperRotation.transform(reference);reference.normalize();
            Quaternionf rotation=new Quaternionf().rotationTo(reference,direction.normalize()).mul(wrapperRotation);
            setWorldTransform(pose,c.body,lower,rotation);pose.finish();setWorldTransform(pose,c.rod,upper,rotation);pose.finish();
        }
    }
    /** Original weapon rotation plus authored grip contact. Source weapon bind
     * axes are +Z forward/+Y up, authored gun is -Z forward/+Y up. Position is
     * fitted to actual currently evaluated digit joints; no gun rescaling and no
     * forced support-hand IK. Call with zero during source grip-release actions.
     */
    public void alignWeaponToRightGrip(GltfSkin.Pose pose,float weight){alignWeaponToPrimaryGrip(pose,weight);}
    /** The original SP body primary grip is authored LEFT/source def_l; FP is
     * an independent source rig. Metadata chooses the actual primary digits. */
    public void alignWeaponToPrimaryGrip(GltfSkin.Pose pose,float weight){alignWeaponToGripNodes(pose,graspNodes,weight);}
    /** Explicit action role override; SP melee uses source/author RIGHT rear
     * grip while LEFT punches. Caller must choose from source action evidence. */
    public void alignWeaponToGrip(GltfSkin.Pose pose,String side,float weight){
        List<Integer> nodes=new ArrayList<>();for(String finger:List.of("middle","ring"))for(String part:List.of("root","tip")){Integer n=nodeNames.get("cube_finger_"+finger+"_"+side+"_"+part);if(n!=null)nodes.add(n);}alignWeaponToGripNodes(pose,nodes.stream().mapToInt(Integer::intValue).toArray(),weight);
    }
    private void alignWeaponToGripNodes(GltfSkin.Pose pose,int[] activeGraspNodes,float weight){
        if(weaponNode<0||activeGraspNodes.length==0)return;
        // All supplied actions retain the gun. A zero caller weight previously
        // released it into an unrelated combat-entry translation trajectory.
        // Until an explicit source drop event is supported, keep a rigid grip.
        weight=1;
        Vector3f grasp=new Vector3f();for(int node:activeGraspNodes)grasp.add(pose.world[node].getTranslation(new Vector3f()));grasp.div(activeGraspNodes.length);
        Quaternionf q=pose.world[sourceWeaponNode].getUnnormalizedRotation(new Quaternionf()).normalize();q.premul(sourceToCube).mul(weaponAxis).normalize();
        Vector3f offset=new Vector3f(gripLocal).mul(authorUnitScale);q.transform(offset);Vector3f goal=new Vector3f(grasp).sub(offset);
        Quaternionf previous=pose.world[weaponNode].getUnnormalizedRotation(new Quaternionf()).normalize();previous.slerp(q,weight);
        Vector3f position=pose.world[weaponNode].getTranslation(new Vector3f()).lerp(goal,weight);
        setWorldTransform(pose,weaponNode,position,previous);pose.finish();
    }
    /** Freeze displayed weapon/source origins before entering a release action.
     * Preserve the original propGun position trajectory relative to that entry
     * point, instead of falling back to a static author wrist offset. */
    public WeaponReleaseAnchor captureWeaponRelease(GltfSkin.Pose pose){
        if(weaponNode<0)return null;
        return new WeaponReleaseAnchor(pose.world[sourceWeaponNode].getTranslation(new Vector3f()),pose.world[sourceRoot].getTranslation(new Vector3f()),pose.world[weaponNode].getTranslation(new Vector3f()));
    }
    public void applyWeaponRelease(GltfSkin.Pose pose,WeaponReleaseAnchor anchor){
        alignWeaponToPrimaryGrip(pose,1);
    }
    /** MC authored-proportion adaptation calibrated in Blockbench. SP LEFT
     * stays the main grip. Gun is diagonal in front of the chest; support palm
     * contacts the fore-end. Torso and source root/leg motion remain untouched. */
    public void fitWeaponSupport(GltfSkin.Pose pose,float weight){
        fitWeaponContact(pose,new Vector3f(0,-6.5f,-20),weight);
    }
    private void fitWeaponContact(GltfSkin.Pose pose,Vector3f contactLocal,float weight){
        weight=Math.max(0,Math.min(1,weight));if(weight<=0||weaponNode<0)return;
        int torso=nodeNames.get("cube_torso"),lh=nodeNames.get("cube_hand_left"),rh=nodeNames.get("cube_hand_right");
        Quaternionf tq=pose.world[torso].getUnnormalizedRotation(new Quaternionf()).normalize(),residual=new Quaternionf();
        if(gunRelativeReferenceReady){Quaternionf sq=pose.world[nodeNames.get("def_c_spineC")].getUnnormalizedRotation(new Quaternionf()).normalize(),sg=pose.world[sourceWeaponNode].getUnnormalizedRotation(new Quaternionf()).normalize();residual.set(sq).invert().mul(sg).mul(new Quaternionf(sourceGunRelativeReference).invert());residual.premul(sourceTorsoToAuthorLocal).mul(new Quaternionf(sourceTorsoToAuthorLocal).invert()).normalize();float angle=2*(float)Math.acos(Math.min(1,Math.abs(residual.w)));if(angle>.22f)residual.set(new Quaternionf().slerp(residual,.22f/angle));}
        Quaternionf gq=new Quaternionf(tq).mul(residual).mul(new Quaternionf(.10574202f,-.43997169f,-.10506486f,.88555345f)).normalize();
        Vector3f gp=pose.world[torso].transformPosition(new Vector3f(-6.2148904f,22.493394f,-21.989294f),new Vector3f());
        Quaternionf lq=new Quaternionf(gq).mul(new Quaternionf(.28519765f,-.42204936f,-.61047507f,.60651203f)).normalize(),rq=new Quaternionf(gq).mul(new Quaternionf(.5f,.5f,.5f,-.5f)).normalize();
        Vector3f currentGrasp=new Vector3f();for(int n:graspNodes)currentGrasp.add(pose.world[n].getTranslation(new Vector3f()));currentGrasp.div(graspNodes.length);Vector3f graspInHand=pose.world[lh].getUnnormalizedRotation(new Quaternionf()).normalize().invert().transform(currentGrasp.sub(pose.world[lh].getTranslation(new Vector3f())));
        Vector3f lo=gq.transform(new Vector3f(gripLocal).mul(authorUnitScale)).sub(lq.transform(new Vector3f(graspInHand))),ro=gq.transform(new Vector3f(contactLocal).mul(authorUnitScale)).sub(rq.transform(new Vector3f(0,-4,2.5f).mul(authorUnitScale)));
        Vector3f lc=pose.world[nodeNames.get("cube_upper_arm_left")].getTranslation(new Vector3f()).sub(lo),rc=pose.world[nodeNames.get("cube_upper_arm_right")].getTranslation(new Vector3f()).sub(ro);
        for(int pass=0;pass<24;pass++){projectSphere(gp,lc,armLength(pose,"left")-.05f);projectSphere(gp,rc,armLength(pose,"right")-.05f);}
        Vector3f old=pose.world[weaponNode].getTranslation(new Vector3f()),newGun=new Vector3f(old).lerp(gp,weight),leftGoal=pose.world[lh].getTranslation(new Vector3f()).lerp(new Vector3f(gp).add(lo),weight),rightGoal=pose.world[rh].getTranslation(new Vector3f()).lerp(new Vector3f(gp).add(ro),weight);
        Quaternionf oldG=pose.world[weaponNode].getUnnormalizedRotation(new Quaternionf()).normalize(),oldL=pose.world[lh].getUnnormalizedRotation(new Quaternionf()).normalize(),oldR=pose.world[rh].getUnnormalizedRotation(new Quaternionf()).normalize();oldG.slerp(gq,weight);oldL.slerp(lq,weight);oldR.slerp(rq,weight);
        // Interpolating wrist and gun independently loses the nonlinear rotated
        // grip offset. Rebuild wrist from the CURRENT interpolated gun/palm.
        leftGoal.set(newGun).add(oldG.transform(new Vector3f(gripLocal).mul(authorUnitScale))).sub(oldL.transform(new Vector3f(graspInHand)));
        solveArmContact(pose,"left",leftGoal,oldL);solveArmContact(pose,"right",rightGoal,oldR);setWorldTransform(pose,weaponNode,newGun,oldG);pose.finish();
        // Blockbench-authored wrap, not source digit deltas on another palm.
        for(String finger:List.of("index","middle","ring","pinky","thumb"))for(String part:List.of("root","tip")){
            Integer n=nodeNames.get("cube_finger_"+finger+"_right_"+part);if(n==null)continue;Quaternionf target=part.equals("tip")?new Quaternionf().rotationX((float)Math.toRadians(-65)):finger.equals("thumb")?new Quaternionf().rotationZYX((float)Math.toRadians(-45),(float)Math.toRadians(25),(float)Math.toRadians(-15)):new Quaternionf().rotationX((float)Math.toRadians(-25));
            int pa=model.nodes[n].parent;Quaternionf localQ=pose.world[pa].getUnnormalizedRotation(new Quaternionf()).normalize().invert().mul(pose.world[n].getUnnormalizedRotation(new Quaternionf()).normalize());localQ.slerp(target,weight);pose.setLocal(n,model.nodes[n].translation,localQ,model.nodes[n].scale);pose.finish();
        }
    }
    private float armLength(GltfSkin.Pose pose,String side){int a=nodeNames.get("cube_upper_arm_"+side),b=nodeNames.get("cube_forearm_"+side),e=nodeNames.get("cube_hand_"+side);return pose.world[a].getTranslation(new Vector3f()).distance(pose.world[b].getTranslation(new Vector3f()))+pose.world[b].getTranslation(new Vector3f()).distance(pose.world[e].getTranslation(new Vector3f()));}
    private static void projectSphere(Vector3f p,Vector3f center,float radius){Vector3f d=new Vector3f(p).sub(center);float length=d.length();if(length>radius)p.set(center).fma(radius/length,d);}
    private void solveArmContact(GltfSkin.Pose pose,String side,Vector3f target,Quaternionf handQ){
        int shoulder=nodeNames.get("cube_upper_arm_"+side),elbow=nodeNames.get("cube_forearm_"+side),hand=nodeNames.get("cube_hand_"+side);Vector3f a=pose.world[shoulder].getTranslation(new Vector3f()),b=pose.world[elbow].getTranslation(new Vector3f()),e=pose.world[hand].getTranslation(new Vector3f());float l1=a.distance(b),l2=b.distance(e);Vector3f goal=new Vector3f(target),axis=new Vector3f(goal).sub(a);float distance=axis.length();if(distance<1e-6f)return;axis.div(distance);float clamped=Math.max(Math.abs(l1-l2)+.015f,Math.min(l1+l2-.015f,distance));if(clamped!=distance){distance=clamped;goal.set(a).fma(distance,axis);}
        Vector3f pole=new Vector3f(b).sub(a);pole.fma(-pole.dot(axis),axis);Vector3f stable=pose.world[targetHip].getUnnormalizedRotation(new Quaternionf()).normalize().transform(new Vector3f(side.equals("right")?1:-1,-.3f,.2f));stable.fma(-stable.dot(axis),axis);if(stable.lengthSquared()>1e-8f){stable.normalize();float c=Math.min(1,pole.length()/.25f);if(pole.lengthSquared()>1e-8f)pole.normalize();else pole.set(stable);pole.lerp(stable,1-c*c);}
        if(pole.lengthSquared()<1e-8f)return;pole.normalize();float along=(l1*l1-l2*l2+distance*distance)/(2*distance),height=(float)Math.sqrt(Math.max(0,l1*l1-along*along));Vector3f joint=new Vector3f(a).fma(along,axis).fma(height,pole);rotateSegment(pose,shoulder,new Vector3f(b).sub(a),new Vector3f(joint).sub(a));Vector3f n=pose.world[elbow].getTranslation(new Vector3f()),f=pose.world[hand].getTranslation(new Vector3f());rotateSegment(pose,elbow,new Vector3f(f).sub(n),new Vector3f(goal).sub(n));setWorldRotation(pose,hand,handQ);pose.finish();
    }
    /** Keep the calibrated left grip/chest gun while the SP right arm performs
     * its original free reach. Short magazine-contact windows override it later. */
    public void stabilizeReloadWeapon(GltfSkin.Pose pose){
        Map<Integer,Quaternionf> right=new LinkedHashMap<>();for(Binding b:bindings){String n=model.nodes[b.target].name;if(n.endsWith("_right")||n.contains("_right_root")||n.contains("_right_tip"))if(n.contains("arm")||n.contains("hand")||n.contains("finger"))right.put(b.target,pose.world[b.target].getUnnormalizedRotation(new Quaternionf()).normalize());}
        fitWeaponSupport(pose,1);Vector3f gp=pose.world[weaponNode].getTranslation(new Vector3f());Quaternionf gq=pose.world[weaponNode].getUnnormalizedRotation(new Quaternionf()).normalize();for(var entry:right.entrySet()){setWorldRotation(pose,entry.getKey(),entry.getValue());pose.finish();}setWorldTransform(pose,weaponNode,gp,gq);pose.finish();
    }
    /** Source SP right-operator trajectory between contact windows; independent
     * magazine attachment switching/short contact IK is explicitly MC adaptation. */
    public void applyMagazineReload(GltfSkin.Pose pose,float phase,float weight){
        Integer mag=nodeNames.get("cube_magazine"),hand=nodeNames.get("cube_hand_right");if(mag==null||hand==null||weaponNode<0||weight<=0)return;phase=Math.max(0,Math.min(1,phase));weight=Math.max(0,Math.min(1,weight));
        float grasp=1-smooth(.13f,.23f,phase),insert=smooth(.55f,.67f,phase)*(1-smooth(.84f,.96f,phase)),contact=Math.max(grasp,insert)*weight;
        float pull=phase<.4f?smooth(.025f,.13f,phase)*12f:(1-smooth(.67f,.81f,phase))*12f;
        fitWeaponContact(pose,new Vector3f(-4.025f,-13.5f-pull,-17),contact);
        // Return the operator to the fore-end after the magazine seats, rather
        // than fading back to a distant raw wrist at the action's last frame.
        float returnWeight=smooth(.84f,.96f,phase)*weight;if(returnWeight>0)fitWeaponSupport(pose,returnWeight);
        Vector3f installed=pose.world[mag].getTranslation(new Vector3f());Quaternionf installedQ=pose.world[mag].getUnnormalizedRotation(new Quaternionf()).normalize(),hq=pose.world[hand].getUnnormalizedRotation(new Quaternionf()).normalize(),freeQ=new Quaternionf(hq).mul(new Quaternionf(.5f,.5f,.5f,-.5f).invert()).normalize();
        Vector3f palm=pose.world[hand].transformPosition(new Vector3f(0,-4,2.5f),new Vector3f()),freeOrigin=new Vector3f(palm).sub(freeQ.transform(new Vector3f(-4.025f,-7,0).mul(authorUnitScale)));
        float detached=(1-smooth(.81f,.84f,phase))*weight;setWorldTransform(pose,mag,new Vector3f(installed).lerp(freeOrigin,detached),new Quaternionf(installedQ).slerp(freeQ,detached));pose.finish();
    }
    private static float smooth(float lo,float hi,float value){float t=Math.max(0,Math.min(1,(value-lo)/(hi-lo)));return t*t*(3-2*t);}

    /** Geometry-based two-bone leg grounding after retargeting. Ground heights
     * are model-space metres (entity floor=0). Weights come from original foot
     * contact events; use zero while airborne and for a swinging leg. The knee
     * keeps its evaluated original bend plane, bone lengths remain unchanged,
     * and the foot's evaluated world orientation is restored after solving.
     * This is MC terrain adaptation, not a claim of Source engine IK parity.
     */
    public void groundFeet(GltfSkin.Pose pose,float leftGroundY,float rightGroundY,float leftWeight,float rightWeight){
        for(GroundChain c:groundChains){
            float weight=Math.max(0,Math.min(1,c.side.equals("left")?leftWeight:rightWeight));if(weight<=0)continue;
            float ground=c.side.equals("left")?leftGroundY:rightGroundY;
            Vector3f a=pose.world[c.hip].getTranslation(new Vector3f()),b=pose.world[c.knee].getTranslation(new Vector3f()),e=pose.world[c.ankle].getTranslation(new Vector3f());
            Quaternionf footRotation=pose.world[c.ankle].getUnnormalizedRotation(new Quaternionf()).normalize();
            float bottom=Float.POSITIVE_INFINITY;
            for(Vector3f p:c.sole)bottom=Math.min(bottom,pose.world[c.ankle].transformPosition(p,new Vector3f()).y);
            if(!Float.isFinite(bottom))continue;
            float correction=ground-bottom;
            // Grounding is a small terrain correction during actual support,
            // not a replacement pose solver for crouches, vaults or raised feet.
            if(Math.abs(correction)>=.22f||weight<.02f)continue;
            float rangeFade=1-Math.max(0,(Math.abs(correction)-.10f)/.12f);weight*=rangeFade*rangeFade*(3-2*rangeFade);
            Vector3f goal=new Vector3f(e);goal.y+=correction*weight;
            float l1=a.distance(b),l2=b.distance(e);if(l1<1e-5f||l2<1e-5f)continue;
            Vector3f axis=new Vector3f(goal).sub(a);float distance=axis.length();if(distance<1e-5f)continue;axis.div(distance);
            if(distance>=l1+l2-.005f||distance<=Math.abs(l1-l2)+.005f)continue;
            Vector3f pole=new Vector3f(b).sub(a);pole.fma(-pole.dot(axis),axis);
            if(pole.lengthSquared()<.0025f)continue;
            pole.normalize();float along=(l1*l1-l2*l2+distance*distance)/(2*distance),height=(float)Math.sqrt(Math.max(0,l1*l1-along*along));
            Vector3f knee=new Vector3f(a).fma(along,axis).fma(height,pole);
            rotateSegment(pose,c.hip,new Vector3f(b).sub(a),new Vector3f(knee).sub(a));
            Vector3f newKnee=pose.world[c.knee].getTranslation(new Vector3f()),newAnkle=pose.world[c.ankle].getTranslation(new Vector3f());
            rotateSegment(pose,c.knee,new Vector3f(newAnkle).sub(newKnee),new Vector3f(goal).sub(newKnee));
            setWorldRotation(pose,c.ankle,footRotation);pose.finish();
        }
    }
    public Vector3f footPosition(GltfSkin.Pose pose,String side,Vector3f out){
        for(GroundChain c:groundChains)if(c.side.equals(side))return pose.world[c.ankle].getTranslation(out);return out.zero();
    }
    public float soleHeight(GltfSkin.Pose pose,String side){
        for(GroundChain c:groundChains)if(c.side.equals(side)){float y=Float.POSITIVE_INFINITY;for(Vector3f p:c.sole)y=Math.min(y,pose.world[c.ankle].transformPosition(p,new Vector3f()).y);return y;}return Float.NaN;
    }
    private void rotateSegment(GltfSkin.Pose pose,int node,Vector3f oldDirection,Vector3f newDirection){
        Quaternionf q=pose.world[node].getUnnormalizedRotation(new Quaternionf()).normalize();
        new Quaternionf().rotationTo(oldDirection.normalize(),newDirection.normalize()).mul(q,q);setWorldRotation(pose,node,q);pose.finish();
    }
    private void setWorldRotation(GltfSkin.Pose pose,int node,Quaternionf rotation){
        int parent=model.nodes[node].parent;Quaternionf q=new Quaternionf(rotation);
        if(parent>=0)q.premul(pose.world[parent].getUnnormalizedRotation(new Quaternionf()).normalize().invert());
        pose.setLocal(node,model.nodes[node].translation,q,model.nodes[node].scale);
    }
    private void setWorldTransform(GltfSkin.Pose pose,int node,Vector3f position,Quaternionf rotation){
        int parent=model.nodes[node].parent;Vector3f t=new Vector3f(position);Quaternionf q=new Quaternionf(rotation);
        if(parent>=0){pose.world[parent].invert(new Matrix4f()).transformPosition(t);q.premul(pose.world[parent].getUnnormalizedRotation(new Quaternionf()).normalize().invert());}
        pose.setLocal(node,t,q,model.nodes[node].scale);
    }
    private static float[] floats(JsonArray a){float[] out=new float[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).getAsFloat();return out;}
}
