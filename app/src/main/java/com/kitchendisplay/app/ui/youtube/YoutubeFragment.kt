package com.kitchendisplay.app.ui.youtube

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import com.kitchendisplay.app.MainActivity
import com.kitchendisplay.app.databinding.FragmentYoutubeBinding

/**
 * Provides an in-app YouTube experience using a WebView.
 * The idle timeout is suppressed while a video is playing.
 */
class YoutubeFragment : Fragment() {

    private var _binding: FragmentYoutubeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentYoutubeBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupWebView()

        // Search on keyboard "Done" / enter
        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN)
            ) {
                performSearch()
                true
            } else false
        }

        binding.btnSearch.setOnClickListener { performSearch() }
    }

    override fun onDestroyView() {
        binding.webview.destroy()
        _binding = null
        (activity as? MainActivity)?.suppressIdleReturn = false
        super.onDestroyView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webview
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Suppress idle return while video is actively playing
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                val isPlayingVideo = newProgress == 100 &&
                        view.url?.contains("watch") == true
                (activity as? MainActivity)?.suppressIdleReturn = isPlayingVideo
                if (!isPlayingVideo) {
                    (activity as? MainActivity)?.resetIdleTimer()
                }
            }
        }

        // Start on the YouTube search/home page
        webView.loadUrl(YOUTUBE_HOME)
    }

    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) {
            binding.webview.loadUrl(YOUTUBE_HOME)
            return
        }
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        binding.webview.loadUrl("$YOUTUBE_SEARCH$encoded")
        binding.etSearch.clearFocus()
    }

    companion object {
        private const val YOUTUBE_HOME = "https://m.youtube.com"
        private const val YOUTUBE_SEARCH = "https://m.youtube.com/results?search_query="
    }
}
