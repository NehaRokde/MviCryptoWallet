package com.example.mvicryptowallet

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.NumberFormat
import java.util.Locale

private val Navy = Color(0xFF101B35)
private val Ink = Color(0xFF17233C)
private val Muted = Color(0xFF78839A)
private val Canvas = Color(0xFFF4F6FA)
private val Mint = Color(0xFF53D6B2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Navy, background = Canvas)) {
                val walletViewModel: WalletViewModel = viewModel(factory = container.viewModelFactory)
                WalletScreen(walletViewModel)
            }
        }
    }
}

data class Transaction(val title: String, val detail: String, val amount: Double, val incoming: Boolean)

sealed interface UserIntent {
    data object LaunchAuth : UserIntent
    data object RefreshData : UserIntent
    data object ToggleBalanceVisibility : UserIntent
}

sealed interface UserState {
    data object Loading : UserState
    data object Unauthenticated : UserState
    data class Success(
        val balance: Double,
        val transactions: List<Transaction>,
        val isBalanceVisible: Boolean
    ) : UserState
    data class Error(val message: String) : UserState
}

sealed interface UserEffect {
    data class ShowToast(val message: String) : UserEffect
}

/** Functional mock biometric flow; real apps can replace this with BiometricPrompt. */
class BiometricAuthManager {
    suspend fun authenticate(): Boolean {
        delay(1_000)
        return true
    }
}

/** Encrypted preferences are preferred; plain preferences keep the demo usable if setup fails. */
class SecureStorage(context: Context) {
    private val preferences = runCatching {
        androidx.security.crypto.EncryptedSharedPreferences.create(
            "wallet_secure_store",
            androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC),
            context,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse { context.getSharedPreferences("wallet_secure_store_fallback", Context.MODE_PRIVATE) }

    fun getAccessToken(): String = preferences.getString(KEY_TOKEN, "expired-demo-token") ?: "expired-demo-token"
    fun saveAccessToken(token: String) { preferences.edit().putString(KEY_TOKEN, token).apply() }

    private companion object { const val KEY_TOKEN = "access_token" }
}

/** Adds the current bearer token to every wallet request. */
class AuthHeaderInterceptor(private val storage: SecureStorage) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer ${storage.getAccessToken()}")
            .build()
        return chain.proceed(request)
    }
}

/** Synchronous mock refresh endpoint, intentionally separate from the protected API client. */
class MockTokenRefreshService {
    fun refreshSynchronously(): String? {
        val refreshClient = OkHttpClient.Builder().addInterceptor { chain ->
            val body = """{"access_token":"wallet-access-${System.currentTimeMillis()}"}"""
            Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val request = Request.Builder().url("https://mock.wallet.local/token/refresh").post("{}".toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            refreshClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string().orEmpty()).optString("access_token").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}

/** OkHttp invokes this after the mock wallet backend returns HTTP 401. */
class TokenAuthenticator(
    private val storage: SecureStorage,
    private val refreshService: MockTokenRefreshService
) : Authenticator {
    override fun authenticate(route: okhttp3.Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val newToken = refreshService.refreshSynchronously() ?: return null
        storage.saveAccessToken(newToken)
        return response.request.newBuilder().header("Authorization", "Bearer $newToken").build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) { count++; prior = prior.priorResponse }
        return count
    }
}

/** Returns 401 for an expired token and a deterministic JSON response after refresh. */
class MockWalletBackendInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = request.header("Authorization").orEmpty().removePrefix("Bearer ")
        val expired = token == "expired-demo-token" || token.isBlank()
        val body = if (expired) "{\"error\":\"token_expired\"}" else "{\"status\":\"ok\"}"
        return Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(if (expired) 401 else 200)
            .message(if (expired) "Unauthorized" else "OK")
            .header("Content-Type", "application/json")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

class WalletRepository(private val client: OkHttpClient) {
    fun refreshWallet(): Boolean {
        val request = Request.Builder().url("https://mock.wallet.local/api/wallet").get().build()
        return runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }
}

class WalletViewModel(
    private val biometricAuthManager: BiometricAuthManager,
    private val repository: WalletRepository
) : ViewModel() {
    private val _state = MutableStateFlow<UserState>(UserState.Unauthenticated)
    val state: StateFlow<UserState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<UserEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<UserEffect> = _effects.asSharedFlow()

    private val transactions = listOf(
        Transaction("Salary Credit", "ACME TECHNOLOGIES · Today", 5_200.00, true),
        Transaction("Grocery Store", "WHOLE FOODS MARKET · Yesterday", 86.42, false),
        Transaction("Electric Bill", "CITY POWER & LIGHT · Oct 28", 124.80, false),
        Transaction("Coffee House", "BLUE BOTTLE COFFEE · Oct 27", 7.50, false),
        Transaction("Online Transfer", "SAVINGS ACCOUNT · Oct 25", 350.00, true),
        Transaction("Pharmacy", "CVS PHARMACY · Oct 24", 23.17, false)
    )

    fun dispatch(intent: UserIntent) {
        when (intent) {
            UserIntent.LaunchAuth -> launchAuthentication()
            UserIntent.RefreshData -> refreshData()
            UserIntent.ToggleBalanceVisibility -> toggleBalance()
        }
    }

    private fun launchAuthentication() {
        if (_state.value == UserState.Loading) return
        viewModelScope.launch {
            _state.value = UserState.Loading
            try {
                if (biometricAuthManager.authenticate()) {
                    _state.value = UserState.Success(12_845.76, transactions, true)
                    _effects.emit(UserEffect.ShowToast("Wallet unlocked securely"))
                } else {
                    _state.value = UserState.Unauthenticated
                    _effects.emit(UserEffect.ShowToast("Biometric verification was not completed"))
                }
            } catch (exception: Exception) {
                _state.value = UserState.Error(exception.message ?: "Authentication failed")
            }
        }
    }

    private fun refreshData() {
        val current = when (val existing = _state.value) {
            is UserState.Success -> existing
            is UserState.Error -> UserState.Success(12_845.76, transactions, true)
            else -> return
        }
        viewModelScope.launch {
            _state.value = UserState.Loading
            val refreshed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.refreshWallet() }
            if (refreshed) {
                _state.value = current.copy(transactions = transactions)
                _effects.emit(UserEffect.ShowToast("Wallet refreshed · secure token renewed"))
            } else {
                _state.value = UserState.Error("Unable to refresh wallet. Please try again.")
            }
        }
    }

    private fun toggleBalance() {
        val current = _state.value as? UserState.Success ?: return
        _state.value = current.copy(isBalanceVisible = !current.isBalanceVisible)
    }
}

class AppContainer(context: Context) {
    private val storage = SecureStorage(context)
    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthHeaderInterceptor(storage))
        .addInterceptor(MockWalletBackendInterceptor())
        .authenticator(TokenAuthenticator(storage, MockTokenRefreshService()))
        .build()
    private val repository = WalletRepository(client)
    val viewModelFactory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(WalletViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return WalletViewModel(BiometricAuthManager(), repository) as T
        }
    }
}

@Composable
private fun WalletScreen(viewModel: WalletViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) { is UserEffect.ShowToast -> snackbarHostState.showSnackbar(effect.message) }
        }
    }
    Scaffold(containerColor = Canvas, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        when (val current = state) {
            UserState.Unauthenticated -> LockedWallet(Modifier.padding(padding)) { viewModel.dispatch(UserIntent.LaunchAuth) }
            UserState.Loading -> LoadingWallet(Modifier.padding(padding))
            is UserState.Error -> ErrorWallet(current.message, Modifier.padding(padding)) { viewModel.dispatch(UserIntent.RefreshData) }
            is UserState.Success -> Dashboard(current, Modifier.padding(padding),
                onToggle = { viewModel.dispatch(UserIntent.ToggleBalanceVisibility) },
                onRefresh = { viewModel.dispatch(UserIntent.RefreshData) })
        }
    }
}

@Composable
private fun LockedWallet(modifier: Modifier = Modifier, onUnlock: () -> Unit) {
    Column(modifier.fillMaxSize().background(Navy).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(104.dp).background(Color(0xFF233250), CircleShape), contentAlignment = Alignment.Center) {
            Text("◈", color = Mint, style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(30.dp))
        Text("NORTHSTAR", color = Mint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Spacer(Modifier.height(12.dp))
        Text("Your money,\nin good hands.", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Private banking built around you.", color = Color(0xFFB8C2D6), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Navy)) {
            Text("◉   Unlock Wallet with Biometrics", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Text("Your biometric data never leaves your device", color = Color(0xFF9BA8BF), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun LoadingWallet(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Navy), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Mint)
        Spacer(Modifier.height(20.dp))
        Text("Securing your session…", color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorWallet(message: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Something went wrong", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp)); Text(message, color = Muted)
        Spacer(Modifier.height(20.dp)); Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun Dashboard(state: UserState.Success, modifier: Modifier = Modifier, onToggle: () -> Unit, onRefresh: () -> Unit) {
    Column(modifier.fillMaxSize().background(Canvas)) {
        Column(Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)).padding(horizontal = 24.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NORTHSTAR  ·  PRIVATE", color = Mint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(9.dp)); Text("Good morning, Alex", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.size(42.dp).background(Color(0xFF263653), CircleShape), contentAlignment = Alignment.Center) { Text("A", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(26.dp))
            Text("TOTAL BALANCE", color = Color(0xFFAAB5CA), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.isBalanceVisible) formatCurrency(state.balance) else "••••••••", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggle) { Text(if (state.isBalanceVisible) "◉" else "◎", color = Mint, style = MaterialTheme.typography.titleLarge) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("↑  2.4%", color = Mint, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp)); Text("this month", color = Color(0xFFAAB5CA), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f)); Text("•• 4821", color = Color(0xFFAAB5CA), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263653))) {
                Text("↻   Refresh Wallet", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent activity", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Ink, fontWeight = FontWeight.Bold)
            Text("View all  →", color = Muted, style = MaterialTheme.typography.labelLarge)
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.transactions) { transaction -> TransactionRow(transaction) }
        }
    }
}

@Composable
private fun TransactionRow(transaction: Transaction) {
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(if (transaction.incoming) Color(0xFFE6F8F2) else Color(0xFFF0F2F7), CircleShape), contentAlignment = Alignment.Center) {
            Text(if (transaction.incoming) "↓" else "↗", color = if (transaction.incoming) Color(0xFF1A9B76) else Navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(transaction.title, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp)); Text(transaction.detail, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text((if (transaction.incoming) "+" else "−") + formatCurrency(transaction.amount), color = if (transaction.incoming) Color(0xFF158A69) else Ink, fontWeight = FontWeight.Bold)
    }
}

private fun formatCurrency(value: Double): String = NumberFormat.getCurrencyInstance(Locale.US).format(value)
