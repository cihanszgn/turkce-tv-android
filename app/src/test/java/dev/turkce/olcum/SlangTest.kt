package dev.turkce.olcum

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SlangTest {
    private val slang = File("src/main/assets/argo-sade.tsv").useLines { Slang.parse(it) }

    @Test fun `street grammar becomes plain English`() {
        assertEquals("I am not too sure", slang.plain("I ain't too sure"))
        assertEquals("he is going to get the money.", slang.plain("he gonna get the money."))
        assertEquals("I am going to call you", slang.plain("I'ma call ya"))
        assertEquals("Look, man, I have to go.", slang.plain("Look, man, I gotta go."))
        assertEquals("What are you talking about", slang.plain("What are you talkin' about"))
    }

    @Test fun `a curly apostrophe from OCR changes nothing`() {
        assertEquals("We are not ready", slang.plain("We ain’t ready"))
    }

    @Test fun `street words and idioms become their meaning`() {
        assertEquals("You want to get cars like this, you have to keep working hard.",
            slang.plain("You wanna get whips like this, you gotta stay on your grind."))
        assertEquals("No one else cares.", slang.plain("No one else gives a shit."))
        assertEquals("They reported me to the police, those snakes.", slang.plain("They ratted me out, those snakes."))
        assertEquals("Where are you, man?", slang.plain("Yo, where the fuck are you, homie?"))
    }

    @Test fun `a misread letter in a word of address is still caught`() {
        assertEquals("Hey guys", slang.plain("Hey níggas"))
    }

    @Test fun `parts of longer words stay as they are`() {
        assertEquals("Without the witness", slang.plain("Without the witness"))
        assertEquals("Maya is at the cosmetic shop", slang.plain("Maya is at the cosmetic shop"))
        assertEquals("Press O to toggle focus on Lamar while driving.", slang.plain("Press O to toggle focus on Lamar while driving."))
    }

    @Test fun `the model gets the plain sentence but the screen keeps its key`() {
        val asked = ArrayList<String>()
        val model = object : LineTranslator {
            override fun translate(source: String): String { asked.add(source); return "tamam" }
            override fun close() = Unit
        }
        val translator = GameTranslator(model, emptyMap(), slang)
        translator.translate("We gotta run now")
        translator.translate("we gotta run now")
        assertEquals(listOf("We have to run now"), asked)
    }

    @Test fun `new street phrases become neutral English`() {
        val examples = listOf(
            "Quit busting my chops." to "Quit bothering me.",
            "He threw me under the bus." to "He blamed me unfairly.",
            "We are in deep shit." to "We are in serious trouble.",
            "Keep it on the down low." to "Keep it secret.",
            "You got some nerve." to "You are very rude.",
            "Beats me." to "I do not know.",
            "He beat me to the car." to "He beat me to the car.",
            "This whole plan went south real quick." to "This whole plan failed real quick.",
            "I will rat him out." to "I will report him to the police.",
            "I got this yesterday from a friend." to "I got this yesterday from a friend.",
            "The barber gave me a close shave." to "The barber gave me a close shave.",
            "Don't snitch on me, man." to "Don't report me to the police, man.",
            "I ain't saying nothing." to "I am not saying anything.",
            "Stop talking smack." to "Stop insulting people.",
            "He chickened out." to "He quit because of fear.",
            "They bailed on us." to "They abandoned us.",
        )
        examples.forEach { (source, expected) -> assertEquals(source, expected, slang.plain(source)) }
    }

    @Test fun `ordinary English with ambiguous street words stays unchanged`() {
        listOf(
            "The hood of the car is open.",
            "Take the ride to the airport.",
            "He got a new job.",
            "The bread is on the table.",
            "The fire is hot and the water is cool.",
        ).forEach { source -> assertEquals(source, source, slang.plain(source)) }
    }
}
