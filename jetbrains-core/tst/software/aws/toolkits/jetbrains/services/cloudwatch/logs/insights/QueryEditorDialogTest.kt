// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.cloudwatch.logs.insights

import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.stub
import com.nhaarman.mockitokotlin2.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryResponse
import software.aws.toolkits.jetbrains.core.MockClientManagerRule
import software.aws.toolkits.jetbrains.utils.rules.JavaCodeInsightTestFixtureRule
import software.aws.toolkits.resources.message
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date

@RunsInEdt
class QueryEditorDialogTest {
    val projectRule = JavaCodeInsightTestFixtureRule()

    @Rule
    @JvmField
    val ruleChain = RuleChain(projectRule, EdtRule())

    @JvmField
    @Rule
    val mockClientManagerRule = MockClientManagerRule(projectRule)

    private lateinit var view: QueryEditor
    private lateinit var sut: QueryEditorDialog
    private lateinit var client: CloudWatchLogsClient

    @Before
    fun setUp() {
        val project = projectRule.project
        view = QueryEditor(project, listOf())
        client = mockClientManagerRule.create()
        sut = QueryEditorDialog(project, "log1", client)

        client.stub {
            on(it.startQuery(any<StartQueryRequest>())).thenReturn(
                StartQueryResponse.builder().queryId("queryId").build()
            )
        }
    }

    @Test
    fun `Absolute or relative time selected`() {
        setViewDetails(absoluteTime = false, relativeTime = false)
        assertThat(sut.validateEditorEntries(view)?.message).contains(message("cloudwatch.logs.validation.timerange"))
    }

    @Test
    fun `Start date must be before end date`() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DATE, -1)
        setViewDetails(absoluteTime = true, startDate = Calendar.getInstance().time, endDate = cal.time)
        assertThat(sut.validateEditorEntries(view)?.message).contains(message("cloudwatch.logs.compare.start.end.date"))
    }

    @Test
    fun `relative time must specify unit`() {
        setViewDetails(relativeTime = true, relativeTimeNumber = "")
        assertThat(sut.validateEditorEntries(view)?.message).contains(message("cloudwatch.logs.no_relative_time_number"))
    }

    @Test
    fun `Search query type is always selected`() {
        setViewDetails(relativeTime = true)
        assertThat(!view.queryLogGroupsRadioButton.isSelected && !view.searchTerm.isSelected).isFalse()

        view.queryLogGroupsRadioButton.doClick()
        assertThat(view.queryLogGroupsRadioButton.isSelected).isTrue()
        assertThat(view.searchTerm.isSelected).isFalse()

        view.searchTerm.doClick()
        assertThat(view.queryLogGroupsRadioButton.isSelected).isFalse()
        assertThat(view.searchTerm.isSelected).isTrue()
    }

    @Test
    fun `No search term entered`() {
        setViewDetails(relativeTime = true, querySearch = true, searchTerm = "")
        assertThat(sut.validateEditorEntries(view)?.message).contains(message("cloudwatch.logs.no_term_entered"))
    }

    @Test
    fun `No query entered`() {
        setViewDetails(relativeTime = true, queryLogs = true, query = "")
        assertThat(sut.validateEditorEntries(view)?.message).contains(message("cloudwatch.logs.no_query_entered"))
    }

    @Test
    fun `Path with relative time and queries correctly entered gets executed`() {
        setViewDetails(relativeTime = true, queryLogs = true, query = "fields @timestamp")
        assertThat(sut.validateEditorEntries(view)?.message).isNull()
    }

    @Test
    fun `Path with absolute time and a search term entered gets executed`() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DATE, -1)
        setViewDetails(absoluteTime = true, endDate = Calendar.getInstance().time, startDate = cal.time, querySearch = true, searchTerm = "Error")
        assertThat(sut.validateEditorEntries(view)?.message).isNull()
    }

    @Test
    fun `Fields extracted correctly from query string`() {
        val fieldsAsSecondPartOfQuery = "filter @message like /Error/ | fields @message"
        val noFieldsQuery = "filter @message like /Error/"
        val onlyFieldsQuery = "fields @logStream, @timestamp"
        val twoFieldsQuery = "fields @timestamp, @logStream | limit 10 | fields @message"
        val fieldsInFilterQuery = "filter @message like /fields/ | fields @logStream"
        assertThat(QueryEditorDialog.getFields(fieldsAsSecondPartOfQuery)).isEqualTo(listOf("@message"))
        assertThat(QueryEditorDialog.getFields(noFieldsQuery)).isEqualTo(listOf("@message", "@timestamp"))
        assertThat(QueryEditorDialog.getFields(onlyFieldsQuery)).isEqualTo(listOf("@logStream", "@timestamp"))
        assertThat(QueryEditorDialog.getFields(twoFieldsQuery)).isEqualTo(listOf("@timestamp", "@logStream", "@message"))
        assertThat(QueryEditorDialog.getFields(fieldsInFilterQuery)).isEqualTo(listOf("@logStream"))
    }

    @Test
    fun `startQuery with absolute time range`() {
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(1))
        val query = QueryDetails(
            mutableListOf("logGroup"),
            TimeRange.AbsoluteRange(Date.from(start), Date.from(end)),
            QueryString.InsightsQueryString("query")
        )

        runBlocking { sut.startQueryAsync(query).await() }

        val captor = argumentCaptor<StartQueryRequest>()
        verify(client).startQuery(captor.capture())

        captor.firstValue.let {
            assertThat(it.logGroupNames()).containsExactly("logGroup")
            assertThat(it.startTime()).isEqualTo(start.epochSecond)
            assertThat(it.endTime()).isEqualTo(end.epochSecond)
            assertThat(it.queryString()).isEqualTo("query")
        }
    }

    @Test
    fun `startQuery with relative time range`() {
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(1))
        val query = QueryDetails(
            mutableListOf("logGroup"),
            TimeRange.RelativeRange(1, ChronoUnit.DAYS),
            QueryString.InsightsQueryString("query")
        )

        runBlocking { sut.startQueryAsync(query).await() }

        val captor = argumentCaptor<StartQueryRequest>()
        verify(client).startQuery(captor.capture())

        captor.firstValue.let {
            assertThat(it.logGroupNames()).containsExactly("logGroup")
            assertThat(it.startTime()).isEqualTo(start.epochSecond)
            assertThat(it.endTime()).isEqualTo(end.epochSecond)
            assertThat(it.queryString()).isEqualTo("query")
        }
    }

    @Test
    fun `startQuery with search term`() {
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(1))
        val query = QueryDetails(
            mutableListOf("logGroup"),
            TimeRange.RelativeRange(1, ChronoUnit.DAYS),
            QueryString.SearchTermQueryString("query")
        )

        runBlocking { sut.startQueryAsync(query).await() }

        val captor = argumentCaptor<StartQueryRequest>()
        verify(client).startQuery(captor.capture())

        captor.firstValue.let {
            assertThat(it.logGroupNames()).containsExactly("logGroup")
            assertThat(it.startTime()).isEqualTo(start.epochSecond)
            assertThat(it.endTime()).isEqualTo(end.epochSecond)
            assertThat(it.queryString()).isEqualTo("fields @message, @timestamp | filter @message like /query/")
        }
    }

    @Test
    fun `startQuery with Insights query language query`() {
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(1))
        val query = QueryDetails(
            mutableListOf("logGroup"),
            TimeRange.RelativeRange(1, ChronoUnit.DAYS),
            QueryString.InsightsQueryString("query")
        )

        runBlocking { sut.startQueryAsync(query).await() }

        val captor = argumentCaptor<StartQueryRequest>()
        verify(client).startQuery(captor.capture())

        captor.firstValue.let {
            assertThat(it.logGroupNames()).containsExactly("logGroup")
            assertThat(it.startTime()).isEqualTo(start.epochSecond)
            assertThat(it.endTime()).isEqualTo(end.epochSecond)
            assertThat(it.queryString()).isEqualTo("query")
        }
    }

    private fun setViewDetails(
        absoluteTime: Boolean = false,
        relativeTime: Boolean = false,
        startDate: Date = Calendar.getInstance().time,
        endDate: Date = Calendar.getInstance().time,
        relativeTimeUnit: String = "Minutes",
        relativeTimeNumber: String = "1",
        querySearch: Boolean = false,
        queryLogs: Boolean = false,
        searchTerm: String = "Example",
        query: String = "Example Query"
    ) {
        view.relativeTimeRadioButton.isSelected = relativeTime
        view.endDate.date = endDate
        view.startDate.date = startDate
        view.absoluteTimeRadioButton.isSelected = absoluteTime
        view.relativeTimeUnit.selectedItem = relativeTimeUnit
        view.relativeTimeNumber.text = relativeTimeNumber
        view.queryLogGroupsRadioButton.isSelected = queryLogs
        view.searchTerm.isSelected = querySearch
        view.querySearchTerm.text = searchTerm
        view.queryBox.text = query
    }
}
