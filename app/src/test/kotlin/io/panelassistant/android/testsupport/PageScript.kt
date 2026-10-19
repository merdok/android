package io.panelassistant.android.testsupport

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs a script the app evaluates in the dashboard page, in Node, against a page whose only globals
 * are the [defined] functions, each a stub that records its arguments the way the HA frontend's
 * callback would receive them. Callers first call [Node.assumeAvailable].
 */
object PageScript {
    data class Call(val function: String, val args: JSONArray)
    data class Outcome(val calls: List<Call>, val error: String?)

    fun run(script: String, vararg defined: String): Outcome {
        val harness = """
            const vm=require('vm');const calls=[];const page={};page.window=page;
            for(const name of ${JSONArray(defined.toList())})page[name]=(...args)=>calls.push([name,args]);
            let error=null;
            try{vm.runInNewContext(${JSONObject.quote(script)},page);}catch(e){error=String(e);}
            process.stdout.write(JSON.stringify({calls,error}));
        """.trimIndent()
        val (code, output) = Node.run(stdin = harness)
        check(code == 0) { output }
        val result = JSONObject(output)
        val calls = result.getJSONArray("calls")
        return Outcome(
            List(calls.length()) { i ->
                val call = calls.getJSONArray(i)
                Call(call.getString(0), call.getJSONArray(1))
            },
            if (result.isNull("error")) null else result.getString("error"),
        )
    }
}
