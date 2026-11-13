package com.example.algohud.council

import android.content.SharedPreferences
import com.example.algohud.core.OrderRequest
import com.example.algohud.core.Side
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCouncilTest {
    private val order = OrderRequest("GOLD", Side.SELL, 0.008, 3900.0, 3905.0,
        "test", pointValue = 400.0, takeProfit = 3887.5)
    private val ctx = CouncilContext("GOLD", order, "test", 3900.0, 0.4,
        emptyList(), emptyList(), 0.0, null, 0.0)

    private fun council(mode: CouncilMode, key: String?): AgentCouncil {
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> when (args!![0]) {
                    AgentCouncil.PREF_MODE -> mode.name
                    AgentCouncil.PREF_KEY -> key
                    else -> args[1]
                }
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
        return AgentCouncil(prefs, log = {}, isKilled = { false }, priceNow = { 3900.0 })
    }

    @Test fun gateWithoutKeyVetoes() = runTest {
        for (key in listOf(null, "", "   ")) {
            assertTrue(council(CouncilMode.GATE, key).review(ctx) is CouncilDecision.Veto)
        }
    }

    @Test fun advisoryWithoutKeyApproves() = runTest {
        assertTrue(council(CouncilMode.ADVISORY, null).review(ctx) is CouncilDecision.Approve)
    }

    @Test fun offWithoutKeyApproves() = runTest {
        assertTrue(council(CouncilMode.OFF, null).review(ctx) is CouncilDecision.Approve)
    }
}
