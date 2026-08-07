package pro.getline.vpn.getline.servers

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSectionPolicyTest {
    /** Node names as the panel emits them, markers included. */
    @Test
    fun ninjaMarkerAnywhereInTheNameMeansLte() {
        assertEquals(ServerSection.Lte, ServerSectionPolicy.sectionOf("🇫🇲🥷NL | LTE hy2"))
        assertEquals(ServerSection.Lte, ServerSectionPolicy.sectionOf("🇫🇲🥷 NL | LTE-1"))
    }

    @Test
    fun televisionMarkerMeansYoutube() {
        assertEquals(ServerSection.Youtube, ServerSectionPolicy.sectionOf("🇷🇺  РФ | 📺 YT no ads"))
    }

    @Test
    fun plainCountryNodeIsMain() {
        assertEquals(ServerSection.Main, ServerSectionPolicy.sectionOf("🇩🇪 Германия | vless"))
    }

    @Test
    fun nestedGroupIsMain() {
        // "⚡️ Авто" is a row like any other and must not be pushed below LTE.
        assertEquals(ServerSection.Main, ServerSectionPolicy.sectionOf("⚡️ Авто"))
    }

    @Test
    fun unparseableNameIsMain() {
        // Never dropped, never hidden: an unknown name stays visible.
        assertEquals(ServerSection.Main, ServerSectionPolicy.sectionOf("Ручной узел"))
    }

    @Test
    fun sectionOrderIsMainThenLteThenYoutube() {
        assertEquals(
            listOf(ServerSection.Main, ServerSection.Lte, ServerSection.Youtube),
            ServerSection.entries.toList(),
        )
    }

    @Test
    fun headingMarksOnlyTheFirstRowOfEachRun() {
        val sections = listOf(
            ServerSection.Main,
            ServerSection.Main,
            ServerSection.Lte,
            ServerSection.Youtube,
        )

        assertEquals(
            listOf(ServerSection.Main, null, ServerSection.Lte, ServerSection.Youtube),
            ServerSectionPolicy.headings(sections),
        )
    }

    @Test
    fun oneSectionGetsNoHeadingAtAll() {
        val sections = List(3) { ServerSection.Main }

        assertEquals(listOf(null, null, null), ServerSectionPolicy.headings(sections))
    }

    @Test
    fun singleRowInASectionedListStillGetsItsHeading() {
        val sections = listOf(ServerSection.Main, ServerSection.Youtube)

        assertEquals(
            listOf(ServerSection.Main, ServerSection.Youtube),
            ServerSectionPolicy.headings(sections),
        )
    }

    @Test
    fun emptyListYieldsNoHeadings() {
        assertEquals(emptyList<ServerSection?>(), ServerSectionPolicy.headings(emptyList()))
    }

    @Test
    fun headingsAlignOneToOneWithRows() {
        val sections = listOf(
            ServerSection.Main,
            ServerSection.Main,
            ServerSection.Lte,
            ServerSection.Lte,
            ServerSection.Youtube,
        )

        assertEquals(sections.size, ServerSectionPolicy.headings(sections).size)
    }
}
