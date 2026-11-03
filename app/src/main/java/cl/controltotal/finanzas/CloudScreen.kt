package cl.controltotal.finanzas

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.NumberFormat
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

private val navy = Color(0xFF0C1025)
private val panel = Color(0xFF27224E)
private val lavender = Color(0xFFC4B5FD)
private val green = Color(0xFF75F0BF)
private fun pesos(value: Long) = "$" + NumberFormat.getIntegerInstance(Locale("es", "CL")).format(value)

private data class CloudEntry(
    val id: String, val title: String, val amount: Long,
    val type: String, val owner: String, val month: String, val createdAt: Long
)

@Composable
fun CloudScreen(onSignedOut: () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid ?: return
    var householdId by remember(uid) { mutableStateOf<String?>(null) }
    var status by remember(uid) { mutableStateOf("Buscando hogar...") }
    var error by remember(uid) { mutableStateOf("") }
    var invite by remember(uid) { mutableStateOf("") }
    var busy by remember(uid) { mutableStateOf(false) }

    DisposableEffect(uid) {
        val listener = db.collection("profiles").document(uid).addSnapshotListener { snapshot, ex ->
            if (ex != null) { error = ex.localizedMessage ?: "Error de conexión"; status = ""; return@addSnapshotListener }
            householdId = snapshot?.getString("householdId")
            status = ""
        }
        onDispose { listener.remove() }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = lavender, background = navy, surface = panel)) {
        Column(Modifier.fillMaxSize().background(navy).padding(16.dp)) {
            Text("CONTROL TOTAL", color = lavender, style = MaterialTheme.typography.headlineMedium)
            Text(auth.currentUser?.email ?: "", color = Color.White, style = MaterialTheme.typography.bodySmall)
            if (status.isNotEmpty()) { Text(status, color = lavender) }
            if (error.isNotEmpty()) { Text(error, color = Color(0xFFFFA3A3)) }
            if (householdId == null && status.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Crea tu hogar o únete al de tu pareja", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(enabled = !busy, onClick = {
                    busy = true; error = ""
                    val id = UUID.randomUUID().toString()
                    val home = mapOf("ownerUid" to uid, "members" to mapOf(uid to true))
                    db.collection("households").document(id).set(home).addOnCompleteListener { task ->
                        if (!task.isSuccessful) { busy = false; error = task.exception?.localizedMessage ?: "Error creando hogar" }
                        else db.collection("profiles").document(uid).set(mapOf("householdId" to id))
                            .addOnCompleteListener { result ->
                                busy = false
                                if (!result.isSuccessful) error = result.exception?.localizedMessage ?: "Error guardando perfil"
                            }
                    }
                }) { Text("Crear hogar familiar") }
                OutlinedTextField(value = invite, onValueChange = { invite = it.trim() },
                    label = { Text("Código del hogar de tu pareja") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && invite.isNotBlank(), onClick = {
                    busy = true; error = ""
                    val code = invite.trim()
                    val homeRef = db.collection("households").document(code)
                    db.runTransaction { transaction ->
                        val snap = transaction.get(homeRef)
                        if (!snap.exists()) throw IllegalStateException("No existe ese hogar")
                        transaction.update(homeRef, "members." + uid, true)
                        code
                    }.addOnCompleteListener { task ->
                        if (!task.isSuccessful) { busy = false; error = task.exception?.localizedMessage ?: "No se pudo unir" }
                        else db.collection("profiles").document(uid).set(mapOf("householdId" to code))
                            .addOnCompleteListener { result ->
                                busy = false
                                if (!result.isSuccessful) error = result.exception?.localizedMessage ?: "Error guardando perfil"
                            }
                    }
                }) { Text("Unirme al hogar") }
            } else if (householdId != null) {
                HouseholdContent(db, householdId!!, uid, onError = { error = it })
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { auth.signOut(); onSignedOut() }) { Text("Cerrar sesión") }
        }
    }
}

@Composable
private fun HouseholdContent(
    db: FirebaseFirestore, householdId: String, uid: String, onError: (String) -> Unit
) {
    var entries by remember(householdId) { mutableStateOf<List<CloudEntry>>(emptyList()) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Gasto") }
    var owner by remember { mutableStateOf("Familiar") }
    var sending by remember { mutableStateOf(false) }
    var budgetText by remember(householdId) { mutableStateOf("") }
    var budgetSaving by remember { mutableStateOf(false) }
    var debtName by remember { mutableStateOf("") }
    var debtTotal by remember { mutableStateOf("") }
    var debtPaid by remember { mutableStateOf("") }
    var debtInstallments by remember { mutableStateOf("") }
    var debtDue by remember { mutableStateOf("") }
    var debtOwner by remember { mutableStateOf("Familiar") }
    var debtSaving by remember { mutableStateOf(false) }
    var debtRecords by remember(householdId) { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<CloudEntry?>(null) }
    var editing by remember { mutableStateOf<CloudEntry?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editAmount by remember { mutableStateOf("") }
    val ref = remember(householdId) { db.collection("households").document(householdId).collection("entries") }
    DisposableEffect(householdId) {
        val listener: ListenerRegistration = ref.addSnapshotListener { snapshot, ex ->
            if (ex != null) { onError(ex.localizedMessage ?: "No se pueden leer movimientos"); return@addSnapshotListener }
            entries = snapshot?.documents?.mapNotNull { d ->
                val n = d.getLong("amount") ?: return@mapNotNull null
                CloudEntry(d.id, d.getString("title") ?: "", n, d.getString("type") ?: "",
                    d.getString("owner") ?: "", d.getString("month") ?: "", d.getLong("createdAt") ?: 0L)
            } ?: emptyList()
        }
        onDispose { listener.remove() }
    }
    DisposableEffect(householdId, month) {
        val subscription = db.collection("households").document(householdId)
            .collection("budgets").document(month.toString())
            .addSnapshotListener { snap, ex ->
                if (ex != null) { onError(ex.localizedMessage ?: "Error al leer presupuesto") }
                else budgetText = (snap?.getLong("amount") ?: 0L).let { if (it == 0L) "" else it.toString() }
            }
        onDispose { subscription.remove() }
    }
    DisposableEffect(householdId) {
        val subscription = db.collection("households").document(householdId)
            .collection("debts").addSnapshotListener { snap, ex ->
                if (ex != null) onError(ex.localizedMessage ?: "Error al leer deudas")
                else debtRecords = snap?.documents?.map { it.data ?: emptyMap<String, Any>() } ?: emptyList()
            }
        onDispose { subscription.remove() }
    }
    val visible = entries.filter { it.month == month.toString() }
    val incomes = visible.filter { it.type == "Ingreso" }.sumOf { it.amount }
    val expenses = visible.filter { it.type == "Gasto" }.sumOf { it.amount }
    val debts = visible.filter { it.type == "Deuda" }.sumOf { it.amount }
    Text("Código privado para compartir con tu pareja:", color = lavender)
    Text(householdId, color = Color.White, style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
        Text(month.toString(), color = Color.White, modifier = Modifier.padding(12.dp))
        TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("›") }
    }
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text("Ingresos familiares: " + pesos(incomes), color = green)
        Text("Sueldo tuyo: " + pesos(visible.filter { it.type == "Ingreso" && it.owner == "Yo" }.sumOf { it.amount }), color = Color.White)
        Text("Sueldo de tu esposa: " + pesos(visible.filter { it.type == "Ingreso" && it.owner == "Esposa" }.sumOf { it.amount }), color = Color.White)
        Text("Gastos: " + pesos(expenses), color = Color.White)
        Text("Deudas registradas (no descontadas): " + pesos(debts), color = lavender)
        Text("El saldo descuenta gastos. Registra cada cuota pagada como Gasto para descontarla una sola vez.", color = lavender, style = MaterialTheme.typography.bodySmall)
        Text("Disponible: " + pesos(incomes - expenses), color = if (incomes >= expenses) green else Color(0xFFFFA3A3))
    }
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text("Presupuesto familiar mensual", color = lavender, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = budgetText, onValueChange = { budgetText = it.filter(Char::isDigit) },
            label = { Text("Límite de gastos en CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        val budget = budgetText.toLongOrNull() ?: 0L
        if (budget > 0L) {
            Text("Restante: " + pesos(budget - expenses), color = if (expenses <= budget) green else Color(0xFFFFA3A3))
            LinearProgressIndicator(progress = { (expenses.toFloat() / budget.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        Button(enabled = !budgetSaving && budget > 0L, onClick = {
            budgetSaving = true
            db.collection("households").document(householdId).collection("budgets").document(month.toString())
                .set(mapOf("amount" to budget, "updatedBy" to uid))
                .addOnCompleteListener { result ->
                    budgetSaving = false
                    if (!result.isSuccessful) onError(result.exception?.localizedMessage ?: "No se pudo guardar presupuesto")
                }
        }) { Text("Guardar presupuesto") }
    }
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text("Control de deudas y cuotas", color = lavender, style = MaterialTheme.typography.titleMedium)
        debtRecords.forEach { debt ->
            val total = (debt["total"] as? Number)?.toLong() ?: 0L
            val paid = (debt["paid"] as? Number)?.toLong() ?: 0L
            Text((debt["title"] as? String ?: "Deuda") + " · " + (debt["owner"] as? String ?: "Familiar"), color = Color.White)
            Text("Pendiente: " + pesos(total - paid) + " · Cuotas: " + (debt["installments"] ?: "?") + " · Vence: " + (debt["dueDate"] ?: "Sin fecha"), color = lavender)
            if (total > 0) LinearProgressIndicator(progress = { (paid.toFloat() / total.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(value = debtName, onValueChange = { debtName = it }, label = { Text("Nombre de la deuda") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = debtTotal, onValueChange = { debtTotal = it.filter(Char::isDigit) }, label = { Text("Monto total CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = debtPaid, onValueChange = { debtPaid = it.filter(Char::isDigit) }, label = { Text("Total pagado CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = debtInstallments, onValueChange = { debtInstallments = it.filter(Char::isDigit) }, label = { Text("Número de cuotas") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = debtDue, onValueChange = { debtDue = it }, label = { Text("Próximo vencimiento AAAA-MM-DD") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Familiar", "Yo", "Esposa").forEach { who ->
                FilterChip(selected = debtOwner == who, onClick = { debtOwner = who }, label = { Text(who) })
            }
        }
        val totalValue = debtTotal.toLongOrNull() ?: 0L
        val paidValue = debtPaid.toLongOrNull() ?: 0L
        val installmentsValue = debtInstallments.toIntOrNull() ?: 0
        val validDate = runCatching { java.time.LocalDate.parse(debtDue) }.isSuccess
        Button(enabled = !debtSaving && debtName.isNotBlank() && totalValue > 0L && paidValue in 0L..totalValue && installmentsValue > 0 && validDate,
            onClick = {
                debtSaving = true
                db.collection("households").document(householdId).collection("debts").add(
                    mapOf("title" to debtName.trim(), "total" to totalValue, "paid" to paidValue,
                        "installments" to installmentsValue, "dueDate" to debtDue, "owner" to debtOwner, "createdBy" to uid)
                ).addOnCompleteListener { result ->
                    debtSaving = false
                    if (result.isSuccessful) { debtName = ""; debtTotal = ""; debtPaid = ""; debtInstallments = ""; debtDue = "" }
                    else onError(result.exception?.localizedMessage ?: "No se pudo guardar deuda")
                }
            }) { Text("Agregar deuda") }
        Text("Las deudas son informativas. Registra cada cuota efectivamente pagada como Gasto para descontarla del disponible.", color = lavender, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text("Análisis mensual", color = lavender, style = MaterialTheme.typography.titleMedium)
        val ratio = if (incomes > 0L) (expenses.toFloat() / incomes.toFloat()).coerceIn(0f, 1f) else 0f
        Text("Gastos sobre ingresos: " + if (incomes > 0L) "${(expenses * 100.0 / incomes).toInt()}%" else "Sin ingresos", color = Color.White)
        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
        Text("Ingresos: " + pesos(incomes) + " · Gastos: " + pesos(expenses), color = Color.White)
    }
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Descripción") },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
        label = { Text("Monto CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Ingreso", "Gasto", "Deuda").forEach { value ->
            FilterChip(selected = type == value, onClick = { type = value }, label = { Text(value) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Familiar", "Yo", "Esposa").forEach { value ->
            FilterChip(selected = owner == value, onClick = { owner = value }, label = { Text(value) })
        }
    }
    Button(enabled = !sending && title.isNotBlank() && (amount.toLongOrNull() ?: 0) > 0,
        onClick = {
            sending = true
            ref.add(mapOf("title" to title.trim(), "amount" to amount.toLong(),
                "type" to type, "owner" to owner, "month" to month.toString(),
                "createdBy" to uid, "createdAt" to System.currentTimeMillis()))
                .addOnCompleteListener { result ->
                    sending = false
                    if (result.isSuccessful) { title = ""; amount = "" }
                    else onError(result.exception?.localizedMessage ?: "No se pudo guardar")
                }
        }) { Text(if (sending) "Guardando..." else "Guardar movimiento") }
    Text("Historial del mes", color = lavender, style = MaterialTheme.typography.titleMedium)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(visible.sortedByDescending { it.createdAt }, key = { it.id }) { item ->
            Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Color.White)
                    Text(item.type + " · " + item.owner + " · " + pesos(item.amount), color = lavender)
                }
                Column {
                    TextButton(onClick = { editing = item; editTitle = item.title; editAmount = item.amount.toString() }) { Text("Editar") }
                    TextButton(onClick = { pendingDelete = item }) { Text("Eliminar") }
                }
            }
        }
    }
    editing?.let { item ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Editar movimiento") },
            text = {
                Column {
                    OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("Descripción") })
                    OutlinedTextField(value = editAmount, onValueChange = { editAmount = it.filter(Char::isDigit) }, label = { Text("Monto CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(enabled = editTitle.isNotBlank() && (editAmount.toLongOrNull() ?: 0L) > 0L, onClick = {
                    val newAmount = editAmount.toLongOrNull() ?: return@TextButton
                    ref.document(item.id).update(mapOf("title" to editTitle.trim(), "amount" to newAmount))
                        .addOnFailureListener { onError(it.localizedMessage ?: "No se pudo editar") }
                    editing = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } }
        )
    }
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("¿Eliminar movimiento?") },
            text = { Text("Se eliminará ${item.title} por ${pesos(item.amount)} del historial compartido.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    ref.document(item.id).delete().addOnFailureListener {
                        onError(it.localizedMessage ?: "No se pudo eliminar")
                    }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } }
        )
    }

}
