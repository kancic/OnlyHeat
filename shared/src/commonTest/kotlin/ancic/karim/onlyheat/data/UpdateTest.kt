package ancic.karim.onlyheat.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class UpdateTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(isNewer("1.10.0", "1.9.3"))
        assertTrue(isNewer("1.0.1", "1.0"))
        assertFalse(isNewer("1.0", "1.0.0"))
        assertFalse(isNewer("1.2.0", "1.10.0"))
    }
}
