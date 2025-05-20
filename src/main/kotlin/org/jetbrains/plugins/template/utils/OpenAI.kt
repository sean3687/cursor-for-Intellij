import org.jetbrains.plugins.template.utils.NotificationUtil
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object OpenAIClient {
    private const val API_KEY = "YOUR_OPENAI_API_KEY"

    fun getSuggestions(context: String): String {
        NotificationUtil.debug(null, "Starting OpenAI API call...")

        val url = URL("https://api.openai.com/v1/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $API_KEY")
        connection.doOutput = true

        // Create properly escaped JSON using a Map and Gson
        val messages = listOf(
            mapOf(
                "role" to "system",
                "content" to "You are a code autocomplete assistant. Provide only the code completion without any explanations. Always maintain proper indentation in your response. Each line after a control flow statement (if, while, for, etc.) should start on a new line with proper indentation. Never put code on the same line as a control flow statement."
            ),
            mapOf(
                "role" to "user",
                "content" to "Complete the following code:\n$context"
            )
        )

        val requestBody = mapOf(
            "model" to "gpt-3.5-turbo",
            "messages" to messages,
            "max_tokens" to 150,
            "temperature" to 0.7,
            "stream" to false
        )

        val gson = Gson()
        val jsonRequest = gson.toJson(requestBody)

        try {
            NotificationUtil.debug(null, "Sending request: $jsonRequest")

            // Send the JSON request
            connection.outputStream.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                    writer.write(jsonRequest)
                    writer.flush()
                }
            }

            // Check the response code
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "No error message"
                NotificationUtil.debug(null, "OpenAI API Error Response: $errorStream")
                throw Exception("OpenAI API returned error code $responseCode: $errorStream")
            }

            // Read the response
            val response = connection.inputStream.bufferedReader().readText()
            NotificationUtil.debug(null, "Received response from OpenAI: $response")

            // Parse JSON properly instead of using regex
            try {
                val jsonResponse = JsonParser.parseString(response).asJsonObject
                val choices = jsonResponse.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val message = choices.get(0).asJsonObject.getAsJsonObject("message")
                    if (message != null) {
                        val content = message.get("content").asString

                        // Process the content - remove code block markers if present
                        val processedContent = content
                            .replace(Regex("^```[a-zA-Z]*\\n"), "")  // Remove opening marker at start
                            .replace(Regex("\\n```$"), "")           // Remove closing marker at end
                            .trim()                                  // Remove any leading/trailing whitespace


                        return processedContent
                    }
                }
                return ""
            } catch (e: Exception) {
                NotificationUtil.debug(null, "Error parsing JSON response: ${e.message}")
                throw e
            }
        } catch (e: Exception) {
            NotificationUtil.debug(null, "Error in OpenAI API call: ${e.message}")
            throw e
        } finally {
            connection.disconnect()
        }
    }
}