package com.alexivanov.receiptvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class RetirementTest {

    private val spending = listOf(
        CategoryCost(Category.GROCERIES, 40, 50_000, 8.0),
        CategoryCost(Category.DINING, 10, 30_000, 2.0),
        CategoryCost(Category.FUEL, 6, 20_000, 1.0),
    )

    /** Within a cent or two: the expected values were worked out separately, at higher precision. */
    private fun assertNear(expected: Long, actual: Long) =
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= 2)

    @Test
    fun `receipts plus other costs less pension, grown with inflation and paid out over retirement`() {
        val plan = RetirementPlan(
            birthYear = 1986, retireAge = 65, planToAge = 95,
            inflationBp = 300, returnBp = 500,
            savedCents = 5_000_000,
            otherMonthlyCents = 150_000, // rent and utilities
            incomeMonthlyCents = 100_000, // pension
            excluded = setOf(Category.FUEL), // no commute once retired
        )
        val e = Retirement.estimate(plan, spending, 2026)

        assertEquals(40, e.age)
        assertEquals(25, e.yearsToRetire)
        assertEquals(30, e.yearsInRetirement)
        assertEquals(80_000, e.receiptsMonthlyCents)
        assertEquals(130_000, e.monthlyTodayCents)
        // $15,600 a year for 30 years at 1.94 % above inflation is 23.015 years of spending.
        assertNear(35_903_830, e.neededTodayCents)
        assertNear(75_174_647, e.neededCents)
        assertNear(3_266_294, e.firstYearCents)
        assertNear(16_931_775, e.savingsAtRetirementCents)
        assertNear(58_242_872, e.shortfallCents)
        assertNear(101_694, e.monthlySavingCents)
        assertEquals(39_000_000, e.ruleOfThumbCents)
    }

    @Test
    fun `with no inflation and no return it is plain years of spending`() {
        val plan = RetirementPlan(birthYear = 1991, retireAge = 65, planToAge = 95, inflationBp = 0, returnBp = 0)
        val e = Retirement.estimate(plan, listOf(CategoryCost(Category.OTHER, 1, 200_000, 1.0)), 2026)
        assertEquals(72_000_000, e.neededTodayCents) // $2,000 a month for 30 years
        assertEquals(72_000_000, e.neededCents)
        assertEquals(200_000, e.monthlySavingCents) // over 30 years of saving
    }

    @Test
    fun `without an age only today's money is known`() {
        val e = Retirement.estimate(RetirementPlan(inflationBp = 0, returnBp = 0), spending, 2026)
        assertNull(e.age)
        assertEquals(0, e.yearsToRetire)
        assertEquals(100_000L * 12 * 30, e.neededTodayCents)
        assertEquals(e.neededTodayCents, e.neededCents)
    }

    @Test
    fun `enough savings means nothing more to save, and being past retirement age leaves no time`() {
        val rich = RetirementPlan(birthYear = 1966, savedCents = 1_000_000_000)
        val covered = Retirement.estimate(rich, spending, 2026)
        assertEquals(0, covered.shortfallCents)
        assertEquals(0, covered.monthlySavingCents)

        val late = Retirement.estimate(RetirementPlan(birthYear = 1956, retireAge = 65), spending, 2026)
        assertEquals(0, late.yearsToRetire)
        assertEquals(late.neededCents, late.shortfallCents)
        assertEquals(0, late.monthlySavingCents)
    }

    @Test
    fun `a pension larger than spending means nothing is needed`() {
        val e = Retirement.estimate(RetirementPlan(birthYear = 1990, incomeMonthlyCents = 1_000_000), spending, 2026)
        assertEquals(0, e.monthlyTodayCents)
        assertEquals(0, e.neededCents)
        assertEquals(0, e.monthlySavingCents)
    }

    @Test
    fun `years of spending match the annuity formula`() {
        assertEquals(BigDecimal(30), Retirement.yearsOfSpending(30, BigDecimal.ZERO))
        // One year is always one year of spending: it is paid at the start.
        assertEquals(0, BigDecimal.ONE.compareTo(Retirement.yearsOfSpending(1, BigDecimal("0.04")).setScale(10, java.math.RoundingMode.HALF_UP)))
    }

    @Test
    fun `the plan survives saving and loading`() {
        val plan = RetirementPlan(
            birthYear = 1980, retireAge = 62, planToAge = 92, inflationBp = 250, returnBp = 650,
            savedCents = 12_345_600, otherMonthlyCents = 210_000, incomeMonthlyCents = 150_000,
            excluded = setOf(Category.FUEL, Category.TRAVEL),
        )
        assertEquals(plan, RetirementPlan.decode(plan.encode()))
        val more = plan.copy(retiredReturnBp = 400, spendingFalls = true, healthcareMonthlyCents = 51_500)
        assertEquals(more, RetirementPlan.decode(more.encode()))
        // A plan saved before these existed has none of their keys, and loads with them off.
        assertEquals(false, RetirementPlan.decode("retire=62;return=650").spendingFalls)
        assertNull(RetirementPlan.decode("retire=62;return=650").retiredReturnBp)
        assertEquals(RetirementPlan(), RetirementPlan.decode(null))
        assertEquals(RetirementPlan(), RetirementPlan.decode("retire=oops;skip=NOPE"))
    }

    @Test
    fun `percentages are read the way people type them`() {
        assertEquals(350, Retirement.parsePercent("3.5"))
        assertEquals(350, Retirement.parsePercent("3,5 %"))
        assertEquals(0, Retirement.parsePercent("0"))
        assertNull(Retirement.parsePercent("25"))
        assertNull(Retirement.parsePercent("-1"))
        assertNull(Retirement.parsePercent("abc"))
        assertEquals("3.5", Retirement.percentText(350))
        assertEquals("5", Retirement.percentText(500))
    }

    @Test
    fun `a lower return once retired needs more, and the range brackets it`() {
        val plan = RetirementPlan(birthYear = 1986, inflationBp = 300, returnBp = 600)
        val same = Retirement.estimate(plan, spending, 2026)
        val safer = Retirement.estimate(plan.copy(retiredReturnBp = 400), spending, 2026)
        assertTrue(safer.neededCents > same.neededCents)
        // Saving until then still grows at 6 %.
        assertEquals(same.savingsAtRetirementCents, safer.savingsAtRetirementCents)

        val (tight, comfortable) = Retirement.range(plan.copy(retiredReturnBp = 400), spending, 2026)
        assertTrue(tight.neededCents < safer.neededCents && safer.neededCents < comfortable.neededCents)
        // Tight is the same as a 5 % return, comfortable as 3 %.
        assertEquals(Retirement.estimate(plan.copy(retiredReturnBp = 500), spending, 2026), tight)
        assertEquals(Retirement.estimate(plan.copy(retiredReturnBp = 300), spending, 2026), comfortable)
        // Never below a 0 % return.
        assertEquals(
            Retirement.estimate(plan.copy(retiredReturnBp = 0), spending, 2026),
            Retirement.range(plan.copy(retiredReturnBp = 50), spending, 2026).second,
        )
    }

    @Test
    fun `spending that falls 1 percent a year needs what a sum of falling years needs`() {
        // No inflation and no return: 30 years of $1,000 a month falling 1 % a year.
        val plan = RetirementPlan(retireAge = 65, planToAge = 95, inflationBp = 0, returnBp = 0, spendingFalls = true)
        val e = Retirement.estimate(plan, listOf(CategoryCost(Category.GROCERIES, 10, 100_000, 1.0)), 2026)
        val years = (0 until 30).sumOf { 0.99.let { f -> Math.pow(f, it.toDouble()) } }
        assertNear(Math.round(1_200_000 * years), e.neededTodayCents)
        assertTrue(e.neededTodayCents < 1_200_000L * 30)
    }

    @Test
    fun `healthcare is part of what retirement pays for`() {
        val plan = RetirementPlan(healthcareMonthlyCents = 51_500)
        val e = Retirement.estimate(plan, spending, 2026)
        assertEquals(100_000L + 51_500, e.monthlyTodayCents)
        // Fidelity's $185,500 over 30 years is about $515 a month.
        assertEquals(51_528, Retirement.usHealthcareMonthly(30))
    }
}
