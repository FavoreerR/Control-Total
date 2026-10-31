package cl.controltotal.finanzas

import android.os.Bundle
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
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

private val bg = Color(0xFF0C1025)
private val card = Color(0xFF27224E)
private val purple = Color(0xFFC4B5FD)
private val mint = Color(0xFF75F0BF)
private val white = Color.White
private fun clp(n: Long): String = "$" + NumberFormat.getIntegerInstance(Locale("es", "CL")).format(n)
data class Entry(val name: String, val amount: Long, val owner: String, val type: String)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ControlTotal(this) }
    }
}
@Composable
fun ControlTotal(context: Context) {
    val prefs = remember { context.getSharedPreferences("control_total_local", Context.MODE_PRIVATE) }
    fun save(entries: List<Entry>, self: String, spouse: String) {
        val data = JSONArray()
        entries.forEach { data.put(JSONObject().put("name", it.name).put("amount", it.amount).put("owner", it.owner).put("type", it.type)) }
        prefs.edit().putString("entries", data.toString()).putString("self_salary", self).putString("spouse_salary", spouse).apply()
    }
    val entries = remember { mutableStateListOf<Entry>().apply {
        runCatching { val data = JSONArray(prefs.getString("entries", "[]")); for (i in 0 until data.length()) { val e = data.getJSONObject(i); add(Entry(e.getString("name"), e.getLong("amount"), e.getString("owner"), e.getString("type"))) } }
    } }
    var mySalary by remember { mutableStateOf(prefs.getString("self_salary", "") ?: "") }
    var spouseSalary by remember { mutableStateOf(prefs.getString("spouse_salary", "") ?: "") }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("Familiar") }
    var type by remember { mutableStateOf("Gasto") }
    var tab by remember { mutableStateOf("Inicio") }
    val income = (mySalary.toLongOrNull() ?: 0L) + (spouseSalary.toLongOrNull() ?: 0L)
    val expenses = entries.filter { it.type == "Gasto" }.sumOf { it.amount }
    val debts = entries.filter { it.type == "Deuda" }.sumOf { it.amount }
    MaterialTheme(colorScheme = darkColorScheme(primary = purple, surface = card, background = bg)) {
        Column(Modifier.fillMaxSize().background(bg).padding(18.dp)) {
            Text("CONTROL TOTAL", color = purple, style = MaterialTheme.typography.labelMedium)
            Text("Finanzas familiares", color = white, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Inicio", "Gastos", "Deudas").forEach { label ->
                    Button(onClick = { tab = label }, colors = ButtonDefaults.buttonColors(containerColor = if (tab == label) Color(0xFF6353AD) else card)) { Text(label) }
                }
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
                            Text(clp(income - expenses), color = mint, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
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
                                    entries.add(0, Entry(name.trim(), n, owner, type))
                                    save(entries, mySalary, spouseSalary)
                                    name = ""; amount = ""
                                }
                            }) { Text("Agregar " + type.lowercase()) }
                        }
                    }
                    items(entries.filter { it.type == if (tab == "Gastos") "Gasto" else "Deuda" }) { entry ->
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
