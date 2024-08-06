package com.feedme.casheye

import android.content.Context
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CashEyeViewModel : ViewModel() {

    private val _uiState: MutableStateFlow<UiState> =
        MutableStateFlow(UiState.Initial)
    val uiState: StateFlow<UiState> =
        _uiState.asStateFlow()

    private val generativeModel = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = BuildConfig.apiKey
    )

    private var textToSpeech: TextToSpeech? = null

    fun initTextToSpeech(context: Context): TextToSpeech {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.US
            }
        }
        return textToSpeech!!
    }

    fun processImage(
        bitmap: Bitmap,
        onTextDetected: (String) -> Unit // This callback now explicitly expects a String
    ) {
        _uiState.value = UiState.Loading

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Modify this part if your API allows sending additional prompts or contexts
                val response = generativeModel.generateContent(
                    content {
                        image(bitmap)
                        // Optionally, you can include a text prompt if your model supports it, e.g.:
                        text("what is the money bills you see in this image. ")
                    }
                )

                val outputContent = response.text ?: ""

                val detectedText = if (outputContent.contains("bill", ignoreCase = true)) {
                    outputContent
                } else {
                    "I cannot see any bills"
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Success(detectedText)
                    onTextDetected(detectedText) // Call the callback with the detected text
                    textToSpeech?.speak(detectedText, TextToSpeech.QUEUE_FLUSH, null, "")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMessage = e.localizedMessage ?: "An error occurred"
                    _uiState.value = UiState.Error(errorMessage)
                    onTextDetected(errorMessage)
                }
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
