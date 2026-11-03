package cl.controltotal.finanzas

import android.os.Bundle
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import java.time.YearMonth

private val bg = Color(0xFF0C1025)
private val card = Color(0xFF27224E)
private val purple = Color(0xFFC4B5FD)
private val mint = Color(0xFF75F0BF)
private val white = Color.White
private fun clp(n: Long): String = "$" + NumberFormat.getIntegerInstance(Locale("es", "CL")).format(n)
data class Entry(val name: String, val amount: Long, val owner: String, val type: String, val month: String = YearMonth.now().toString())
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ControlTotal(this) }
    }
}
@Composable
fun ControlTotal(context: Context) {
    val auth = remember { FirebaseAuth.getInstance() }
    var signedIn by remember { mutableStateOf(auth.currentUser != null) }
    if (!signedIn) {
        AuthScreen(onSignedIn = { signedIn = true })
        return
    }
    val activity = context as MainActivity
    var unlocked by remember(signedIn) { mutableStateOf(false) }
    DisposableEffect(activity, signedIn) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                unlocked = false
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    var lockError by remember { mutableStateOf("") }
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val canAuthenticate = remember(activity) {
        BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }
    if (canAuthenticate && !unlocked) {
        MaterialTheme(colorScheme = darkColorScheme(primary = purple, background = bg)) {
            Column(Modifier.fillMaxSize().background(bg).padding(24.dp),
                verticalArrangement = Arrangement.Center) {
                Text("Control Total protegido", color = white, style = MaterialTheme.typography.headlineMedium)
                Text("Confirma tu identidad para ver tus finanzas.", color = purple)
                if (lockError.isNotBlank()) Text(lockError, color = Color(0xFFFFA3A3))
                Button(onClick = {
                    lockError = ""
                    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                unlocked = true
                            }
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                lockError = errString.toString()
                            }
                        })
                    val info = BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Desbloquear Control Total")
                        .setSubtitle("Usa tu huella, rostro compatible o bloqueo del dispositivo")
                        .setAllowedAuthenticators(authenticators)
                        .build()
                    prompt.authenticate(info)
                }) { Text("Desbloquear") }
                TextButton(onClick = { auth.signOut(); signedIn = false }) { Text("Cerrar sesión") }
            }
        }
    } else {
        CloudScreen(onSignedOut = { signedIn = false })
    }
    return
    val prefs = remember { context.getSharedPreferences("control_total_local", Context.MODE_PRIVATE) }
    fun save(entries: List<Entry>, self: String, spouse: String) {
        val data = JSONArray()
        entries.forEach { data.put(JSONObject().put("name", it.name).put("amount", it.amount).put("owner", it.owner).put("type", it.type).put("month", it.month)) }
        prefs.edit().putString("entries", data.toString()).putString("self_salary", self).putString("spouse_salary", spouse).apply()
    }
    val entries = remember { mutableStateListOf<Entry>().apply {
        runCatching { val data = JSONArray(prefs.getString("entries", "[]")); for (i in 0 until data.length()) { val e = data.getJSONObject(i); add(Entry(e.getString("name"), e.getLong("amount"), e.getString("owner"), e.getString("type"), e.optString("month", YearMonth.now().toString()))) } }
    } }
    var mySalary by remember { mutableStateOf(prefs.getString("self_salary", "") ?: "") }
    var spouseSalary by remember { mutableStateOf(prefs.getString("spouse_salary", "") ?: "") }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("Familiar") }
    var type by remember { mutableStateOf("Gasto") }
    var tab by remember { mutableStateOf("Inicio") }
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    val monthlyEntries = entries.filter { it.month == selectedMonth.toString() }
    var budget by remember { mutableStateOf(prefs.getString("monthly_budget", "") ?: "") }
    val income = (mySalary.toLongOrNull() ?: 0L) + (spouseSalary.toLongOrNull() ?: 0L)
    val expenses = monthlyEntries.filter { it.type == "Gasto" }.sumOf { it.amount }
    val debts = monthlyEntries.filter { it.type == "Deuda" }.sumOf { it.amount }
    val budgetAmount = budget.toLongOrNull() ?: 0L
    val spentRatio = if (budgetAmount > 0L) (expenses.toFloat() / budgetAmount.toFloat()).coerceIn(0f, 1f) else 0f
    MaterialTheme(colorScheme = darkColorScheme(primary = purple, surface = card, background = bg)) {
        Column(Modifier.fillMaxSize().background(bg).padding(18.dp)) {
            Text("CONTROL TOTAL", color = purple, style = MaterialTheme.typography.labelMedium)
            Text("Finanzas familiares", color = white, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(auth.currentUser?.email ?: "Cuenta", color = purple, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { auth.signOut(); signedIn = false }) { Text("Cerrar sesión") }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("Inicio", "Gastos", "Deudas", "Historial").forEach { label ->
                    Button(onClick = { tab = label }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = if (tab == label) Color(0xFF6353AD) else card)) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { selectedMonth = selectedMonth.minusMonths(1) }) { Text("‹ Anterior") }
                Text(selectedMonth.toString(), color = white, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { selectedMonth = selectedMonth.plusMonths(1) }, enabled = selectedMonth < YearMonth.now()) { Text("Siguiente ›") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == "Inicio") {
                    item {
                        Panel {
                            Text("Ingresos familiares", color = purple)
                            Text(clp(income), color = white, style = MaterialTheme.typography.headlineLarge)
                            Text("Tu sueldo", color = white)
                            MoneyField(mySalary) { mySalary = it; save(entries, mySalary, spouseSalary) }
                            Text("Sueldo de tu esposa", color = white)
                            MoneyField(spouseSalary) { spouseSalary = it; save(entries, mySalary, spouseSalary) }
                        }
                    }
                    item {
                        Panel {
                            Text("Gastos: " + clp(expenses), color = white)
                            Text("Deudas registradas: " + clp(debts), color = white)
                            Text("Disponible (descontando gastos)", color = mint)
                            Text(clp(income - expenses), color = if (income >= expenses) mint else Color(0xFFFF9A9A), style = MaterialTheme.typography.headlineMedium)
                            HorizontalDivider()
                            Text("Presupuesto de gastos", color = purple)
                            MoneyField(budget) { budget = it; prefs.edit().putString("monthly_budget", it).apply() }
                            if (budgetAmount > 0L) {
                                LinearProgressIndicator(progress = { spentRatio }, modifier = Modifier.fillMaxWidth(), color = if (expenses > budgetAmount) Color(0xFFFF9A9A) else mint)
                                Text("Utilizado: " + clp(expenses) + " de " + clp(budgetAmount), color = white)
                                Text(if (expenses > budgetAmount) "Presupuesto excedido en " + clp(expenses - budgetAmount) else "Restante: " + clp(budgetAmount - expenses), color = if (expenses > budgetAmount) Color(0xFFFF9A9A) else mint)
                            }
                        }
                    }
                }
                if (tab == "Historial") {
                    item { Text("Movimientos de " + selectedMonth.toString(), color = purple, style = MaterialTheme.typography.titleLarge) }
                    items(monthlyEntries) { entry -> Panel {
                        Text(entry.name, color = white, fontWeight = FontWeight.Bold)
                        Text(entry.type + " · " + entry.owner + " · " + clp(entry.amount), color = purple)
                    } }
                }
                if (tab == "Gastos" || tab == "Deudas") {
                    item {
                        Panel {
                            Text("Registrar movimiento", color = white, style = MaterialTheme.typography.titleLarge)
                            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Descripción") }, singleLine = true)
                            MoneyField(amount) { amount = it }
                            Text("Corresponde a", color = white)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("Familiar", "Yo", "Esposa").forEach { who ->
                                    TextButton(onClick = { owner = who }) { Text(if (owner == who) "✓ $who" else who) }
                                }
                            }
                            type = if (tab == "Gastos") "Gasto" else "Deuda"
                            Button(onClick = {
                                val n = amount.toLongOrNull()
                                if (name.isNotBlank() && n != null && n > 0) {
                                    entries.add(0, Entry(name.trim(), n, owner, type, selectedMonth.toString()))
                                    save(entries, mySalary, spouseSalary)
                                    name = ""; amount = ""
                                }
                            }) { Text("Agregar " + type.lowercase()) }
                        }
                    }
                    items(monthlyEntries.filter { it.type == if (tab == "Gastos") "Gasto" else "Deuda" }) { entry ->
                        Panel {
                            Text(entry.name, color = white, fontWeight = FontWeight.Bold)
                            Text(clp(entry.amount) + " · " + entry.owner, color = purple)
                            TextButton(onClick = { entries.remove(entry); save(entries, mySalary, spouseSalary) }) { Text("Eliminar movimiento") }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(card, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
@Composable
private fun MoneyField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { onChange(it.filter(Char::isDigit)) }, label = { Text("Monto en CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun AuthScreen(onSignedIn: () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = darkColorScheme(primary = purple, surface = card, background = bg)) {
        Column(
            modifier = Modifier.fillMaxSize().background(bg).padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("CONTROL TOTAL", color = purple, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Tus finanzas familiares", color = white)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() },
                label = { Text("Correo electrónico") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Contraseña (mínimo 6 caracteres)") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            if (error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = Color(0xFFFFA3A3))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !busy && email.contains("@") && password.length >= 6,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    error = ""
                    val task = if (creating) auth.createUserWithEmailAndPassword(email, password)
                               else auth.signInWithEmailAndPassword(email, password)
                    task.addOnCompleteListener { result ->
                        busy = false
                        if (result.isSuccessful) onSignedIn()
                        else error = result.exception?.localizedMessage ?: "No se pudo iniciar sesión"
                    }
                }
            ) { Text(if (busy) "Espera..." else if (creating) "Crear cuenta" else "Ingresar") }
            TextButton(onClick = { creating = !creating; error = "" }) {
                Text(if (creating) "Ya tengo cuenta" else "Crear una cuenta nueva")
            }
            TextButton(enabled = !busy && email.contains("@"), onClick = {
                busy = true
                auth.sendPasswordResetEmail(email).addOnCompleteListener { task ->
                    busy = false
                    error = if (task.isSuccessful) "Si la cuenta existe, recibirás instrucciones por correo."
                            else "No se pudo solicitar la recuperación. Intenta nuevamente."
                }
            }) { Text("Olvidé mi contraseña") }
        }
    }
}
