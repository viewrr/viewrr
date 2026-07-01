package wtf.jobin.editorial

import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifierTest {
    @Test fun reviewInTitle() {
        assertEquals(EditorialKind.REVIEW, Classifier.classify("'Poor Things' Review: a bold fantasia"))
    }

    @Test fun oscarNomination() {
        assertEquals(EditorialKind.OSCAR_NOM, Classifier.classify("Oppenheimer leads Oscar nominations for 2024"))
    }

    @Test fun goldenGlobeNomination() {
        assertEquals(EditorialKind.GLOBE_NOM, Classifier.classify("Barbie snubbed among Golden Globe nominations"))
    }

    @Test fun festivalWin() {
        assertEquals(EditorialKind.FESTIVAL_WIN, Classifier.classify("Anora wins the Palme d'Or at Cannes"))
    }

    @Test fun reviewMentioningOscarStaysReview() {
        // precision guard: "oscar" without a nomination verb must NOT become an award badge
        assertEquals(EditorialKind.REVIEW, Classifier.classify("'Maestro' Review: an Oscar-worthy turn"))
    }

    @Test fun unrelatedIsIgnored() {
        assertEquals(EditorialKind.IGNORE, Classifier.classify("Netflix quarterly subscriber numbers climb"))
    }

    @Test fun festivalWithoutWinVerbIsNotHighlight() {
        // mentions Cannes but no win — not a festival-win badge (falls through to ignore/review)
        assertEquals(EditorialKind.IGNORE, Classifier.classify("Five films to watch at the Cannes lineup"))
    }

    @Test fun badgeLabels() {
        assertEquals("Oscar Nominee", Classifier.badgeLabel(EditorialKind.OSCAR_NOM))
        assertEquals("Festival Winner", Classifier.badgeLabel(EditorialKind.FESTIVAL_WIN))
    }
}
