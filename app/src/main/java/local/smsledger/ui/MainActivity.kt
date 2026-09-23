package local.smsledger.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import local.smsledger.core.*
import local.smsledger.data.*
import local.smsledger.work.Scheduler
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF176A5A), secondary = Color(0xFF54645E), surface = Color(0xFFF8FAF8))) {
                Surface(Modifier.fillMaxSize()) { LedgerApp() }
            }
        }
    }
}
private fun money(value: Long) = "₹" + BigDecimal.valueOf(value, 2).toPlainString()
private fun date(value: Long) = if(value == 0L) "—" else Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))

@Composable
fun LedgerApp(vm: LedgerViewModel = viewModel()) {
    val context = LocalContext.current
    val rows by vm.transactions.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val instruments by vm.instruments.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("Dashboard") }
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) }
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var daily by rememberSaveable { mutableStateOf(prefs.getBoolean("daily", true)) }
    var publicExport by rememberSaveable { mutableStateOf(prefs.getBoolean("publicExport", false)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) { Scheduler.batch(context); Scheduler.daily(context, daily) }
        else vm.message.value = "SMS access is required to import transactions. You can grant it in app settings."
    }
    LaunchedEffect(Unit) { if(hasPermission) Scheduler.daily(context, daily) }
    val runBatch: () -> Unit = {
        if(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            hasPermission = true; Scheduler.batch(context); vm.message.value = "Batch queued. Progress appears below."
        } else permission.launch(Manifest.permission.READ_SMS)
    }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Dashboard", "Transactions", "Needs Review", "Settings").forEach { title ->
                NavigationBarItem(selected = screen == title, onClick = { screen = title }, icon = { Text(when(title) { "Dashboard" -> "◷"; "Transactions" -> "≡"; "Needs Review" -> "!"; else -> "⚙" }) }, label = { Text(title) })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("SMS Ledger", style = MaterialTheme.typography.headlineMedium)
            Text("Local only · Batch expense tracker", style = MaterialTheme.typography.bodySmall)
            if(message.isNotEmpty()) TextButton(onClick = { vm.message.value = "" }) { Text("$message  ×") }
            if(!hasPermission) {
                Text("Reads your inbox only after permission. OTP and unrelated messages are discarded; SMS bodies are never stored.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { permission.launch(Manifest.permission.READ_SMS) }) { Text("Allow SMS & Import History") }
            }
            when(screen) {
                "Dashboard" -> Dashboard(rows, batch, export, runBatch, {
                    publicExport = true; prefs.edit().putBoolean("publicExport", true).apply()
                    Scheduler.export(context); vm.message.value = "Export queued to Downloads/SMS Ledger/SMS-Ledger.xlsx."
                })
                "Transactions", "Needs Review" -> {
                    var search by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search merchant or exact entry") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    val visible = rows.filter { row ->
                        (screen != "Needs Review" || row.entry.status in setOf(ReviewStatus.NEEDS_REVIEW, ReviewStatus.UNKNOWN)) &&
                            (search.isBlank() || "${row.entry.merchant} ${row.entry.exactEntry}".contains(search, true))
                    }
                    Text("${visible.size} transactions", Modifier.padding(vertical = 8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if(visible.isEmpty()) item { Text("No transactions here. Run a batch to import your SMS.") }
                        items(visible, key = { it.id }) { row ->
                            TransactionCard(row, { editing = row }, { vm.status(row, ReviewStatus.CONFIRMED) }, { vm.status(row, ReviewStatus.IGNORED) })
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
                else -> SettingsScreen(daily, { daily = it; prefs.edit().putBoolean("daily", it).apply(); Scheduler.daily(context, it) },
                    publicExport, { publicExport = it; prefs.edit().putBoolean("publicExport", it).apply(); Scheduler.export(context) },
                    rules, instruments, vm, { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) })
            }
        }
    }
    editing?.let { row -> EditTransaction(row, onDismiss = { editing = null }, onSave = { entry, learn, exact -> vm.save(row, entry, learn, exact); editing = null }) }
}
@Composable
private fun Dashboard(rows: List<TransactionEntity>, batch: BatchState?, export: ExportState?, run: () -> Unit, exportNow: () -> Unit) {
    val month = YearMonth.now().toString()
    val values = rows.map { it.entry }.filter { it.currency == "INR" && it.month(ZoneId.systemDefault()) == month }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This month · $month · INR", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        val income = values.sumOf(Totals::income); val expense = values.sumOf(Totals::expense); val refunds = values.sumOf(Totals::refunds)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Income" to income, "Expenses" to expense, "Net Savings" to income-expense+refunds,
                    "Credit Card Spend" to values.sumOf(Totals::credit),
                    "Debit/Bank Spend" to values.sumOf { Totals.debit(it)+Totals.bank(it) },
                    "UPI Spend" to values.sumOf(Totals::upi)).forEach { (label, amount) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(money(amount)) }
                }
            }
        }
        Text("Confirmed entries only. Net savings includes refunds. UPI can overlap card/bank spending. Transfers and cash withdrawals are excluded.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = run, modifier = Modifier.fillMaxWidth()) { Text("Run Batch Now") }
        OutlinedButton(onClick = exportNow, modifier = Modifier.fillMaxWidth()) { Text("Export Excel") }
        Text("Export Excel enables an automatically updated local copy in Downloads/SMS Ledger. Edit entries here; workbook edits are overwritten.", style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Batch status", style = MaterialTheme.typography.titleMedium)
                Text("Last Batch: ${date(batch?.completed ?: 0)}")
                if(batch?.active == true) Text(if(batch.scanComplete) "Waiting for Excel export" else "Import in progress / queued for continuation")
                Text("SMS Checked: ${batch?.checked ?: 0}")
                Text("Transactions Found: ${batch?.found ?: 0}")
                Text("Auto Categorised: ${batch?.automatic ?: 0}")
                Text("Needs Review: ${batch?.review ?: 0}")
                Text("Duplicates Ignored: ${batch?.duplicates ?: 0}")
                Text("Excel Updated: ${if(export?.dirty == true) "Pending" else date(export?.updated ?: 0)}")
                val error = batch?.error?.ifBlank { null } ?: export?.error
                if(!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
@Composable
private fun TransactionCard(row: TransactionEntity, edit: () -> Unit, confirm: () -> Unit, ignore: () -> Unit) {
    val t = row.entry
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${t.currency} ${t.amount?.toPlainString() ?: "Amount unknown"} · ${t.merchant}", style = MaterialTheme.typography.titleMedium)
            Text(date(t.timestamp)); Text("${t.mode.label} · ${t.funding.label} · ${t.instrumentLabel}")
            Text("${t.type.label} · ${t.category}${if(t.subcategory.isBlank()) "" else " / ${t.subcategory}"}")
            Text("Exact Entry: ${t.exactEntry.ifBlank { "—" }}")
            Text("${t.status.label} · ${t.confidence}%", style = MaterialTheme.typography.bodySmall)
            if(t.notes.isNotBlank()) Text(t.notes, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = confirm, enabled = t.status != ReviewStatus.CONFIRMED) { Text("Confirm") }
                TextButton(onClick = edit) { Text("Edit") }
                TextButton(onClick = ignore, enabled = t.status != ReviewStatus.IGNORED) { Text("Ignore") }
            }
        }
    }
}
@Composable
private fun <T> Picker(label: String, value: T, options: List<T>, name: (T) -> String, change: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${name(value)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(name(option)) }, onClick = { change(option); expanded = false }) }
        }
    }
}
@Composable
private fun EditTransaction(row: TransactionEntity, onDismiss: () -> Unit, onSave: (Transaction, Boolean, Boolean) -> Unit) {
    val t = row.entry
    var merchant by remember { mutableStateOf(t.merchant) }; var exact by remember { mutableStateOf(t.exactEntry) }
    var category by remember { mutableStateOf(t.category) }; var subcategory by remember { mutableStateOf(t.subcategory) }
    var type by remember { mutableStateOf(t.type) }; var mode by remember { mutableStateOf(t.mode) }
    var funding by remember { mutableStateOf(t.funding) }; var notes by remember { mutableStateOf(t.notes) }
    var amount by remember { mutableStateOf(t.amount?.toPlainString().orEmpty()) }
    var currency by remember { mutableStateOf(t.currency) }; var bank by remember { mutableStateOf(t.bank) }
    var last4 by remember { mutableStateOf(t.last4) }; var instrument by remember { mutableStateOf(t.instrument) }
    var datetime by remember { mutableStateOf(Instant.ofEpochMilli(t.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))) }
    var learn by remember { mutableStateOf(true) }; var learnExact by remember { mutableStateOf(false) }
    val minor = runCatching { BigDecimal(amount).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it >= 0 }
    val timestamp = runCatching { LocalDateTime.parse(datetime, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Correct transaction") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(exact, { exact = it }, label = { Text("Exact Entry (optional)") })
            OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant") })
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, isError = minor == null)
            OutlinedTextField(datetime, { datetime = it }, label = { Text("yyyy-MM-dd HH:mm:ss") }, isError = timestamp == null)
            Picker("Currency", currency, listOf("INR", "USD", "EUR", "GBP"), { it }, { currency = it })
            Picker("Category", category, Categories.all, { it }, { category = it })
            OutlinedTextField(subcategory, { subcategory = it }, label = { Text("Subcategory") })
            Picker("Type", type, TransactionType.entries, { it.label }, { type = it })
            Picker("Payment Mode", mode, PaymentMode.entries, { it.label }, { mode = it })
            Picker("Funding Source", funding, FundingSource.entries, { it.label }, { funding = it })
            OutlinedTextField(bank, { bank = it }, label = { Text("Bank / issuer") })
            OutlinedTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, label = { Text("Last 4 digits only") })
            OutlinedTextField(instrument, { instrument = it }, label = { Text("Instrument name") })
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
            Row { Checkbox(learn, { learn = it }); Text("Learn this merchant/category for future transactions of this type") }
            Row { Checkbox(learnExact, { learnExact = it }, enabled = learn); Text("Also create an Exact Entry rule") }
            Text("Exact Entry is reused only if this second option is selected. Confidence records the original automated assessment.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(t.copy(merchant = merchant, exactEntry = exact, category = category, subcategory = subcategory,
            type = type, mode = mode, funding = funding, notes = notes, amountMinor = minor, currency = currency,
            bank = bank, last4 = last4, instrument = instrument, timestamp = timestamp!!), learn, learn && learnExact) },
            enabled = minor != null && timestamp != null && merchant.isNotBlank() && (last4.isBlank() || last4.length == 4)) { Text("Save & Confirm") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable
private fun SettingsScreen(daily: Boolean, onDaily: (Boolean) -> Unit, publicExport: Boolean, onPublicExport: (Boolean) -> Unit,
    rules: List<MerchantRuleEntity>, instruments: List<InstrumentEntity>, vm: LedgerViewModel, appSettings: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row { Switch(daily, onDaily); Text("Daily batch (Android may delay execution)", Modifier.padding(12.dp)) }
        Row { Switch(publicExport, onPublicExport); Text("Update local Downloads workbook", Modifier.padding(12.dp)) }
        Text("Room is the source of truth. No Internet permission, ads or analytics. App backup is disabled. Downloads copies remain until you delete them.")
        Text("Do not move the workbook into a cloud-synced folder if you want it to stay local.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = appSettings) { Text("Open Android app settings") }
        Text("Payment instruments", style = MaterialTheme.typography.titleLarge)
        Text("Match bank + last four digits. Used when an SMS does not identify its funding source. Explicit SMS evidence takes priority.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { adding = true }) { Text("Add instrument") }
        instruments.forEach { instrument ->
            Text("${instrument.bank} · •${instrument.last4} · ${instrument.name} · ${instrument.funding.label}")
            TextButton(onClick = { vm.removeInstrument(instrument) }) { Text("Remove") }
        }
        Text("Learned merchant rules", style = MaterialTheme.typography.titleLarge)
        if(rules.isEmpty()) Text("Correct a transaction to teach a rule.")
        rules.forEach { rule ->
            Text("${rule.key} → ${rule.merchant} / ${rule.category}")
            if(rule.exactEntry != null) Text("Exact Entry rule: ${rule.exactEntry}")
            TextButton(onClick = { vm.removeRule(rule) }) { Text("Remove rule") }
        }
        Text("Samsung: allow background activity and remove SMS Ledger from Sleeping/Deep sleeping apps. Force-stop pauses jobs until you open the app again.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
    }
    if(adding) InstrumentDialog({ adding = false }) { bank, last4, name, funding ->
        vm.instrument(bank, last4, name, funding); adding = false
    }
}
@Composable
private fun InstrumentDialog(dismiss: () -> Unit, save: (String, String, String, FundingSource) -> Unit) {
    var bank by remember { mutableStateOf("HDFC") }; var last4 by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }; var funding by remember { mutableStateOf(FundingSource.CREDIT_CARD) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Payment instrument") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("Bank", bank, listOf("HDFC", "ICICI", "SBI", "Axis", "Kotak", "IDFC", "Indian Bank", "Canara", "PNB"), { it }, { bank = it })
            OutlinedTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, label = { Text("Last four digits") })
            OutlinedTextField(name, { name = it }, label = { Text("e.g. Tata Neu HDFC RuPay") })
            Picker("Funding", funding, FundingSource.entries.filter { it != FundingSource.UNKNOWN }, { it.label }, { funding = it })
            Text("Do not enter a full card or account number.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { save(bank, last4, name, funding) }, enabled = last4.length == 4 && name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
