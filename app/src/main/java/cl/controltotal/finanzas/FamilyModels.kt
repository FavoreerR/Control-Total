package cl.controltotal.finanzas

/** Amounts are stored in Chilean pesos (integer CLP), never floating point. */
enum class Member { SELF, SPOUSE, FAMILY }
enum class EntryType { INCOME, EXPENSE, DEBT_PAYMENT }
enum class DebtScope { PERSONAL, SHARED }

data class MoneyEntry(
    val id: String,
    val householdId: String,
    val member: Member,
    val type: EntryType,
    val amountClp: Long,
    val category: String,
    val note: String,
    val createdAtMillis: Long,
    val month: String // YYYY-MM
) {
    init {
        require(amountClp > 0) { "Amount must be positive" }
        require(Regex("\\d{4}-(0[1-9]|1[0-2])").matches(month)) { "Invalid month" }
    }
}

data class FamilyDebt(
    val id: String,
    val householdId: String,
    val title: String,
    val scope: DebtScope,
    val responsibleMember: Member,
    val originalClp: Long,
    val paidClp: Long
) {
    init {
        require(originalClp >= 0 && paidClp in 0..originalClp)
        require(scope == DebtScope.SHARED || responsibleMember != Member.FAMILY)
    }
    val remainingClp: Long get() = originalClp - paidClp
}

data class MonthlySummary(
    val selfIncomeClp: Long,
    val spouseIncomeClp: Long,
    val expensesClp: Long,
    val debtPaymentsClp: Long
) {
    val familyIncomeClp get() = selfIncomeClp + spouseIncomeClp
    val availableClp get() = familyIncomeClp - expensesClp - debtPaymentsClp
}

object FamilyCalculator {
    fun summarize(entries: List<MoneyEntry>, householdId: String, month: String): MonthlySummary {
        val relevant = entries.filter { it.householdId == householdId && it.month == month }
        return MonthlySummary(
            selfIncomeClp = relevant.filter { it.type == EntryType.INCOME && it.member == Member.SELF }.sumOf { it.amountClp },
            spouseIncomeClp = relevant.filter { it.type == EntryType.INCOME && it.member == Member.SPOUSE }.sumOf { it.amountClp },
            expensesClp = relevant.filter { it.type == EntryType.EXPENSE }.sumOf { it.amountClp },
            debtPaymentsClp = relevant.filter { it.type == EntryType.DEBT_PAYMENT }.sumOf { it.amountClp }
        )
    }
}
