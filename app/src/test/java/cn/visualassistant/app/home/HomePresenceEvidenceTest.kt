package cn.visualassistant.app.home
import org.junit.Assert.*
import org.junit.Test
class HomePresenceEvidenceTest{
    @Test fun backendIsOnlyCoarseHomeCandidate(){assertTrue(HomePresenceEvidence(true,false).candidateHome);assertFalse(HomePresenceEvidence(false,true).candidateHome)}
    @Test fun evidenceDoesNotClaimRoomOrPose(){assertFalse(HomePresenceEvidence(true,true).description.contains("卧室"));assertFalse(HomePresenceEvidence(true,true).description.contains("定位成功"))}
}
