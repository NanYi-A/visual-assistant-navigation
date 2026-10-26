import unittest
import numpy as np
try:
    from .merge_segment import transform_pose
except ImportError:  # run as a plain directory, not as a package
    from merge_segment import transform_pose

class MergeSegmentTest(unittest.TestCase):
    def test_pose_and_direction_use_rigid_transform(self):
        transform=np.eye(4);transform[:3,3]=[2,3,4]
        result=transform_pose({'x':1,'y':2,'z':3,'forwardX':0,'forwardY':0,'forwardZ':-1},transform)
        self.assertEqual((3.,5.,7.),(result['x'],result['y'],result['z']))
        self.assertEqual((0.,0.,-1.),(result['forwardX'],result['forwardY'],result['forwardZ']))

if __name__=='__main__':unittest.main()
