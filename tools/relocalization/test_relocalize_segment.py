import unittest
import numpy as np
try:
    from .relocalize_segment import consensus,rotation_degrees,alignment_allowed,shortlist_pairs
except ImportError:  # run as a plain directory, not as a package
    from relocalize_segment import consensus,rotation_degrees,alignment_allowed,shortlist_pairs

class RelocalizationTest(unittest.TestCase):
    def candidate(self,parent,child,x,inliers=12):
        t=np.eye(4);t[0,3]=x;return {'transform':t,'parent':parent,'child':child,'inliers':inliers}
    def test_consensus_requires_independent_frames(self):
        one=[self.candidate('p1','c1',0),self.candidate('p1','c2',.01)]
        self.assertEqual([],consensus(one))
        many=one+[self.candidate('p2','c1',-.01),self.candidate('p2','c2',.02)]
        self.assertEqual(4,len(consensus(many)))
    def test_rotation_distance(self):
        a=np.eye(4);b=np.eye(4);angle=np.deg2rad(10);b[:3,:3]=[[np.cos(angle),0,np.sin(angle)],[0,1,0],[-np.sin(angle),0,np.cos(angle)]]
        self.assertAlmostEqual(10,rotation_degrees(a,b),places=4)
    def test_strong_multiframe_consensus_tolerates_mobile_depth_noise(self):
        agreed=[self.candidate(f'p{i}',f'c{i}',0,inliers=30) for i in range(4)]
        geometry={'overlapFraction':.25,'medianDistanceMeters':.13}
        self.assertTrue(alignment_allowed(agreed,120,geometry))
        self.assertFalse(alignment_allowed(agreed,120,{'overlapFraction':.05,'medianDistanceMeters':.13}))
    def test_dense_consensus_accepts_slightly_noisier_geometry(self):
        agreed=[self.candidate(f'p{i}',f'c{i}',0,inliers=30) for i in range(10)]
        self.assertTrue(alignment_allowed(agreed,300,{'overlapFraction':.30,'medianDistanceMeters':.18}))
        self.assertFalse(alignment_allowed(agreed[:9],300,{'overlapFraction':.30,'medianDistanceMeters':.18}))
        self.assertFalse(alignment_allowed(agreed,299,{'overlapFraction':.30,'medianDistanceMeters':.18}))
        self.assertFalse(alignment_allowed(agreed,300,{'overlapFraction':.29,'medianDistanceMeters':.18}))
        self.assertFalse(alignment_allowed(agreed,300,{'overlapFraction':.30,'medianDistanceMeters':.181}))
    def test_orb_shortlist_prefers_matching_parent_and_caps_pair_count(self):
        rng=np.random.default_rng(17)
        matching=rng.integers(0,256,(240,320,3),dtype=np.uint8)
        unrelated=rng.integers(0,256,(240,320,3),dtype=np.uint8)
        child=[{'path':'c1','rgb':matching},{'path':'c2','rgb':matching.copy()}]
        parent=[{'path':'p-match','rgb':matching.copy()}]+[{'path':f'p{i}','rgb':unrelated.copy()} for i in range(9)]
        pairs=shortlist_pairs(parent,child,limit=3)
        self.assertLessEqual(len(pairs),6)
        self.assertTrue(any(p['path']=='p-match' for p,_ in pairs))

    def test_orb_shortlist_falls_back_when_all_scores_are_below_cutoff(self):
        # Feature-poor frames must still reach the learned matcher. ORB is retrieval,
        # not the final localization gate.
        blank=np.zeros((120,160,3),dtype=np.uint8)
        child=[{'path':'c1','rgb':blank}]
        parent=[{'path':f'p{i}','rgb':blank.copy()} for i in range(5)]
        pairs=shortlist_pairs(parent,child,limit=4)
        self.assertEqual(2,len(pairs))
        self.assertEqual(['p0','p1'],[p['path'] for p,_ in pairs])

if __name__=='__main__':unittest.main()
