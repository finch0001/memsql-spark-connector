package com.memsql.spark

import com.memsql.spark.SQLGen.Relation
import org.apache.log4j.{Level, LogManager}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.types._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

class SQLPushdownTest extends IntegrationSuiteBase with BeforeAndAfterEach with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    super.beforeAll()
    super.beforeEach() // we want to run beforeEach to set up a spark session

    // need to specific explicit schemas - otherwise Spark will infer them
    // incorrectly from the JSON file
    val usersSchema = StructType(
      StructField("id", LongType)
        :: StructField("first_name", StringType)
        :: StructField("last_name", StringType)
        :: StructField("email", StringType)
        :: StructField("owns_house", BooleanType)
        :: StructField("favorite_color", StringType, nullable = true)
        :: StructField("age", IntegerType)
        :: StructField("birthday", DateType)
        :: Nil)

    writeTable("testdb.users",
               spark.read.schema(usersSchema).json("src/test/resources/data/users.json"))

    val moviesSchema = StructType(
      StructField("id", LongType)
        :: StructField("title", StringType)
        :: StructField("genre", StringType)
        :: StructField("critic_review", StringType, nullable = true)
        :: StructField("critic_rating", FloatType, nullable = true)
        :: Nil)

    writeTable("testdb.movies",
               spark.read.schema(moviesSchema).json("src/test/resources/data/movies.json"))

    val reviewsSchema = StructType(
      StructField("user_id", LongType)
        :: StructField("movie_id", LongType)
        :: StructField("rating", FloatType)
        :: StructField("review", StringType)
        :: StructField("created", TimestampType)
        :: Nil)

    writeTable("testdb.reviews",
               spark.read.schema(reviewsSchema).json("src/test/resources/data/reviews.json"))

    writeTable("testdb.users_sample",
               spark.read
                 .format("memsql")
                 .load("testdb.users")
                 .sample(0.5)
                 .limit(10))
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    spark.sql("create database testdb")
    spark.sql("create database testdb_nopushdown")
    spark.sql("create database testdb_jdbc")

    def makeTables(sourceTable: String) = {
      spark.sql(
        s"create table testdb.$sourceTable using memsql options ('dbtable'='testdb.$sourceTable')")
      spark.sql(
        s"create table testdb_nopushdown.$sourceTable using memsql options ('dbtable'='testdb.$sourceTable','disablePushdown'='true')")
      spark.sql(s"create table testdb_jdbc.$sourceTable using jdbc options (${jdbcOptionsSQL(
        s"testdb.$sourceTable")})")
    }

    makeTables("users")
    makeTables("users_sample")
    makeTables("movies")
    makeTables("reviews")

    spark.udf.register("stringIdentity", (s: String) => s)
  }

  def extractQueriesFromPlan(root: LogicalPlan): Seq[String] = {
    root
      .map({
        case Relation(relation) => relation.sql
        case _                  => ""
      })
      .sorted
  }

  def testCodegenDeterminism(q: String): Unit = {
    val logManager    = LogManager.getLogger("com.memsql.spark")
    var setLogToTrace = false

    if (logManager.isTraceEnabled) {
      logManager.setLevel(Level.DEBUG)
      setLogToTrace = true
    }

    assert(
      extractQueriesFromPlan(spark.sql(q).queryExecution.optimizedPlan) ==
        extractQueriesFromPlan(spark.sql(q).queryExecution.optimizedPlan),
      "All generated MemSQL queries should be the same"
    )

    if (setLogToTrace) {
      logManager.setLevel(Level.TRACE)
    }
  }

  def testQuery(q: String,
                alreadyOrdered: Boolean = false,
                expectPartialPushdown: Boolean = false,
                expectSingleRead: Boolean = false,
                expectEmpty: Boolean = false,
                pushdown: Boolean = true): Unit = {

    spark.sql("use testdb_jdbc")
    val jdbcDF = spark.sql(q)
    // verify that the jdbc DF works first
    jdbcDF.collect()
    if (pushdown) { spark.sql("use testdb") } else { spark.sql("use testdb_nopushdown") }

    testCodegenDeterminism(q)

    val memsqlDF = spark.sql(q)

    if (!continuousIntegration) { memsqlDF.show(4) }

    if (expectEmpty) {
      assert(memsqlDF.count == 0, "result is expected to be empty")
    } else {
      assert(memsqlDF.count > 0, "result is expected to not be empty")
    }

    if (expectSingleRead) {
      assert(memsqlDF.rdd.getNumPartitions == 1,
             "query is expected to read from a single partition")
    } else {
      assert(memsqlDF.rdd.getNumPartitions > 1,
             "query is expected to read from multiple partitions")
    }

    assert(
      (memsqlDF.queryExecution.optimizedPlan match {
        case SQLGen.Relation(_) => false
        case _                  => true
      }) == expectPartialPushdown,
      s"the optimized plan does not match expectPartialPushdown=$expectPartialPushdown"
    )

    try {
      def changeTypes(df: DataFrame): DataFrame = {
        var newDf = df
        df.schema
          .foreach(x =>
            x.dataType match {
              // Replace all Floats with Doubles, because JDBC connector converts FLOAT to DoubleType when MemSQL connector converts it to FloatType
              // Replace all Decimals with Doubles, because assertApproximateDataFrameEquality compare Decimals for strong equality
              case _: DecimalType | FloatType =>
                newDf = newDf.withColumn(x.name, newDf(x.name).cast(DoubleType))
              // Replace all Shorts with Integers, because JDBC connector converts SMALLINT to IntegerType when MemSQL connector converts it to ShortType
              case _: ShortType =>
                newDf = newDf.withColumn(x.name, newDf(x.name).cast(IntegerType))
              // Replace all CalendarIntervals with Strings, because assertApproximateDataFrameEquality can't sort CalendarIntervals
              case _: CalendarIntervalType =>
                newDf = newDf.withColumn(x.name, newDf(x.name).cast(StringType))
              case _ =>
          })
        newDf
      }
      assertApproximateDataFrameEquality(changeTypes(memsqlDF),
                                         changeTypes(jdbcDF),
                                         0.1,
                                         orderedComparison = alreadyOrdered)
    } catch {
      case e: Throwable =>
        if (continuousIntegration) { println(memsqlDF.explain(true)) }
        throw e
    }
  }

  def testOrderedQuery(q: String,
                       expectPartialPushdown: Boolean = false,
                       pushdown: Boolean = true): Unit = {
    // order by in MemSQL requires single read
    testQuery(q,
              alreadyOrdered = true,
              expectPartialPushdown = expectPartialPushdown,
              expectSingleRead = true,
              pushdown = pushdown)
  }

  def testSingleReadQuery(q: String,
                          alreadyOrdered: Boolean = false,
                          expectPartialPushdown: Boolean = false,
                          pushdown: Boolean = true): Unit = {
    testQuery(q,
              alreadyOrdered = alreadyOrdered,
              expectPartialPushdown = expectPartialPushdown,
              expectSingleRead = true,
              pushdown = pushdown)
  }

  describe("Attributes") {
    describe("successful pushdown") {
      it("Attribute") { testQuery("select id from users") }
      it("Alias") { testQuery("select id as user_id from users") }
    }
    describe("unsuccessful pushdown") {
      it("alias with udf") {
        testQuery("select stringIdentity(id) as user_id from users", expectPartialPushdown = true)
      }
    }
  }

  describe("Literals") {
    describe("successful pushdown") {
      it("string") { testSingleReadQuery("select 'string' from users") }
      it("null") { testSingleReadQuery("select null from users") }
      describe("boolean") {
        it("true") { testQuery("select true from users") }
        it("false") { testQuery("select false from users") }
      }

      it("byte") { testSingleReadQuery("select 100Y from users") }
      it("short") { testQuery("select 100S from users") }
      it("integer") { testQuery("select 100 from users") }
      it("long") { testSingleReadQuery("select 100L from users") }

      it("float") { testQuery("select 1.1 as x from users") }
      it("double") { testQuery("select 1.1D as x from users") }
      it("decimal") { testQuery("select 1.1BD as x from users") }

      it("datetime") { testQuery("select date '1997-11-11' as x from users") }
    }

    describe("unsuccessful pushdown") {
      it("interval") {
        testQuery("select interval 1 year 1 month as x from users", expectPartialPushdown = true)
      }
      it("binary literal") {
        testQuery("select X'123456' from users", expectPartialPushdown = true)
      }
    }
  }

  describe("Variable Expressions") {
    describe("Coalesce") {
      it("one non-null value") { testQuery("select coalesce(id) from users") }
      it("one null value") { testSingleReadQuery("select coalesce(null) from users") }
      it("a lot of values") { testQuery("select coalesce(null, id, null, id+1) from users") }
      it("a lot of nulls") { testSingleReadQuery("select coalesce(null, null, null) from users") }
      it("partial pushdown with udf") {
        testQuery(
          "select coalesce('qwerty', 'bob', stringIdentity(first_name), 'alice') from users",
          expectPartialPushdown = true)
      }
    }

    describe("Least") {
      it("a lot of ints") { testQuery("select least(id+5, id, 5, id+1) from users") }
      it("a lot of strings") {
        testQuery("select least('qwerty', 'bob', first_name, 'alice') from users")
      }
      // MemSQL returns NULL if at least one argument is NULL, when spark skips nulls
      // it("ints with null") { testQuery("select least(null, id, null, id+1) from users") }
      it("a lot of nulls") { testSingleReadQuery("select least(null, null, null) from users") }
      it("partial pushdown with udf") {
        testQuery("select least('qwerty', 'bob', stringIdentity(first_name), 'alice') from users",
                  expectPartialPushdown = true)
      }
    }

    describe("Greatest") {
      it("a lot of ints") { testQuery("select greatest(id+5, id, 5, id+1) from users") }
      it("a lot of strings") {
        testQuery("select greatest('qwerty', 'bob', first_name, 'alice') from users")
      }
      // MemSQL returns NULL if at least one argument is NULL, when spark skips nulls
      // it("ints with null") { testQuery("select greatest(null, id, null, id+1) from users") }
      it("a lot of nulls") { testSingleReadQuery("select greatest(null, null, null) from users") }
      it("partial pushdown with udf") {
        testQuery(
          "select greatest('qwerty', 'bob', stringIdentity(first_name), 'alice') from users",
          expectPartialPushdown = true)
      }
    }

    describe("Concat") {
      it("a lot of ints") { testQuery("select concat(id+5, id, 5, id+1) from users") }
      it("a lot of strings") {
        testQuery("select concat('qwerty', 'bob', first_name, 'alice') from users")
      }
      it("ints with null") { testQuery("select concat(null, id, null, id+1) from users") }
      it("a lot of nulls") { testSingleReadQuery("select concat(null, null, null) from users") }
      it("int and string") { testQuery("select concat(id, first_name) from users") }
      it("partial pushdown with udf") {
        testQuery("select concat('qwerty', 'bob', stringIdentity(first_name), 'alice') from users",
                  expectPartialPushdown = true)
      }
    }

    describe("Elt") {
      it("a lot of ints") { testQuery("select elt(id+5, id, 5, id+1) from users") }
      it("a lot of strings") {
        testQuery("select elt('qwerty', 'bob', first_name, 'alice') from users")
      }
      it("ints with null") { testQuery("select elt(null, id, null, id+1) from users") }
      it("a lot of nulls") { testQuery("select elt(null, null, null) from users") }
      it("int and string") { testQuery("select elt(id, first_name) from users") }
      it("partial pushdown with udf") {
        testQuery("select elt('qwerty', 'bob', stringIdentity(first_name), 'alice') from users",
                  expectPartialPushdown = true)
      }
    }
  }

  describe("Aggregate Expressions") {
    describe("Average") {
      it("ints") { testSingleReadQuery("select avg(user_id) as x from reviews") }
      it("floats") { testSingleReadQuery("select avg(rating) as x from reviews") }
      it("floats with nulls") { testSingleReadQuery("select avg(critic_rating) as x from movies") }
      it("partial pushdown with udf") {
        testSingleReadQuery("select avg(stringIdentity(user_id)) as x from reviews",
                            expectPartialPushdown = true)
      }
    }
    describe("StddevPop") {
      it("ints") { testSingleReadQuery("select stddev_pop(user_id) as x from reviews") }
      it("floats") { testSingleReadQuery("select stddev_pop(rating) as x from reviews") }
      it("floats with nulls") {
        testSingleReadQuery("select stddev_pop(critic_rating) as x from movies")
      }
      it("partial pushdown with udf") {
        testSingleReadQuery("select stddev_pop(stringIdentity(user_id)) as x from reviews",
                            expectPartialPushdown = true)
      }
    }
    describe("StddevSamp") {
      it("ints") { testSingleReadQuery("select stddev_samp(user_id) as x from reviews") }
      it("floats") { testSingleReadQuery("select stddev_samp(rating) as x from reviews") }
      it("floats with nulls") {
        testSingleReadQuery("select stddev_samp(critic_rating) as x from movies")
      }
      it("partial pushdown with udf") {
        testSingleReadQuery("select stddev_samp(stringIdentity(user_id)) as x from reviews",
                            expectPartialPushdown = true)
      }
    }
    describe("VariancePop") {
      it("ints") { testSingleReadQuery("select var_pop(user_id) as x from reviews") }
      it("floats") { testSingleReadQuery("select var_pop(rating) as x from reviews") }
      it("floats with nulls") {
        testSingleReadQuery("select var_pop(critic_rating) as x from movies")
      }
      it("partial pushdown with udf") {
        testSingleReadQuery("select var_pop(stringIdentity(user_id)) as x from reviews",
                            expectPartialPushdown = true)
      }
    }
    describe("VarianceSamp") {
      it("ints") { testSingleReadQuery("select var_samp(user_id) as x from reviews") }
      it("floats") { testSingleReadQuery("select var_samp(rating) as x from reviews") }
      it("floats with nulls") {
        testSingleReadQuery("select var_samp(critic_rating) as x from movies")
      }
      it("partial pushdown with udf") {
        testSingleReadQuery("select var_samp(stringIdentity(user_id)) as x from reviews",
                            expectPartialPushdown = true)
      }
    }
    describe("Max") {
      it("ints") { testSingleReadQuery("select  max(user_id) as x from reviews") }
      it("floats") { testSingleReadQuery("select max(rating) as x from reviews") }
      it("strings") { testSingleReadQuery("select max(first_name) as x from users") }
      it("floats with nulls") { testSingleReadQuery("select max(critic_rating) as x from movies") }
      it("partial pushdown with udf") {
        testSingleReadQuery("select max(stringIdentity(user_id)) as x from reviews",
                            expectPartialPushdown = true)
      }
    }
    describe("Min") {
      it("ints") { testSingleReadQuery("select min(user_id) as x from reviews") }
      it("floats") { testSingleReadQuery("select min(rating) as x from reviews") }
      it("strings") { testSingleReadQuery("select min(first_name) as x from users") }
      it("floats with nulls") { testSingleReadQuery("select min(critic_rating) as x from movies") }
      it("partial pushdown with udf") {
        testSingleReadQuery("select min(stringIdentity(user_id)) as x from reviews",
                            expectPartialPushdown = true)
      }
    }
    describe("Sum") {
      // We cast the output, because MemSQL SUM returns DECIMAL(41, 0)
      // which is not supported by spark (spark maximum decimal precision is 38)
      it("ints") { testSingleReadQuery("select cast(sum(age) as decimal(20, 0)) as x from users") }
      it("floats") { testSingleReadQuery("select sum(rating) as x from reviews") }
      it("floats with nulls") { testSingleReadQuery("select sum(critic_rating) as x from movies") }
      it("partial pushdown with udf") {
        testSingleReadQuery("select sum(stringIdentity(user_id)) as x from reviews",
                            expectPartialPushdown = true)
      }
    }
    describe("First") {
      it("succeeds") { testSingleReadQuery("select first(first_name) from users group by id") }
      it("partial pushdown with udf") {
        testSingleReadQuery("select first(stringIdentity(first_name)) from users group by id",
                            expectPartialPushdown = true)
      }
    }
    describe("Last") {
      it("succeeds") { testSingleReadQuery("select last(first_name) from users group by id") }
      it("partial pushdown with udf") {
        testSingleReadQuery("select last(stringIdentity(first_name)) from users group by id",
                            expectPartialPushdown = true)
      }
    }
    describe("Count") {
      it("all") { testSingleReadQuery("select count(*) from users") }
      it("distinct") { testSingleReadQuery("select count(distinct first_name) from users") }
      it("partial pushdown with udf (all)") {
        testSingleReadQuery("select count(stringIdentity(first_name)) from users group by id",
                            expectPartialPushdown = true)
      }
      it("partial pushdown with udf (distinct)") {
        testSingleReadQuery(
          "select count(distinct stringIdentity(first_name)) from users group by id",
          expectPartialPushdown = true)
      }
    }
    it("top 3 email domains") {
      testOrderedQuery(
        """
          |   select domain, count(*) from (
          |     select substring(email, locate('@', email) + 1) as domain
          |     from users
          |   )
          |   group by 1
          |   order by 2 desc, 1 asc
          |   limit 3
          |""".stripMargin
      )
    }
  }

  describe("arithmetic") {
    describe("Add") {
      it("numbers") { testQuery("select user_id + movie_id as x from reviews") }
      it("floats") { testQuery("select rating + 1.0 as x from reviews") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select stringIdentity(user_id) + movie_id as x from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select user_id + stringIdentity(movie_id) as x from reviews",
                  expectPartialPushdown = true)
      }
    }
    describe("Subtract") {
      it("numbers") { testQuery("select user_id - movie_id as x from reviews") }
      it("floats") { testQuery("select rating - 1.0 as x from reviews") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select stringIdentity(user_id) - movie_id as x from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select user_id - stringIdentity(movie_id) as x from reviews",
                  expectPartialPushdown = true)
      }
    }
    describe("Multiply") {
      it("numbers") { testQuery("select user_id * movie_id as x from reviews") }
      it("floats") { testQuery("select rating * 1.3 as x from reviews") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select stringIdentity(user_id) * movie_id as x from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select user_id * stringIdentity(movie_id) as x from reviews",
                  expectPartialPushdown = true)
      }
    }
    describe("Divide") {
      it("numbers") { testQuery("select user_id / movie_id as x from reviews") }
      it("floats") { testQuery("select rating / 1.3 as x from reviews") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select stringIdentity(user_id) / movie_id as x from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select user_id / stringIdentity(movie_id) as x from reviews",
                  expectPartialPushdown = true)
      }
    }
    describe("Remainder") {
      it("numbers") { testQuery("select user_id % movie_id as x from reviews") }
      it("floats") { testQuery("select rating % 4 as x from reviews") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select stringIdentity(user_id) % movie_id as x from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select user_id % stringIdentity(movie_id) as x from reviews",
                  expectPartialPushdown = true)
      }
    }
    describe("Pmod") {
      it("numbers") { testQuery("select pmod(user_id, movie_id) as x from reviews") }
      it("floats") { testQuery("select pmod(rating, 4) as x from reviews") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select pmod(stringIdentity(user_id), movie_id) as x from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select pmod(user_id, stringIdentity(movie_id)) as x from reviews",
                  expectPartialPushdown = true)
      }
    }
  }

  describe("bitwiseExpressions") {
    describe("And") {
      it("succeeds") { testQuery("select user_id & movie_id as x from reviews") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select cast(stringIdentity(user_id) as integer) & movie_id as x from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select user_id & cast(stringIdentity(movie_id) as integer) as x from reviews",
                  expectPartialPushdown = true)
      }
    }
    describe("Or") {
      it("numbers") { testQuery("select user_id | movie_id as x from reviews") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select cast(stringIdentity(user_id) as integer) | movie_id as x from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select user_id | cast(stringIdentity(movie_id) as integer) as x from reviews",
                  expectPartialPushdown = true)
      }
    }
    describe("Xor") {
      it("numbers") { testQuery("select user_id ^ movie_id as x from reviews") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select cast(stringIdentity(user_id) as integer) ^ movie_id as x from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select user_id ^ cast(stringIdentity(movie_id) as integer) as x from reviews",
                  expectPartialPushdown = true)
      }
    }
  }

  describe("sanity test disablePushdown") {
    def testNoPushdownQuery(q: String, expectSingleRead: Boolean = false): Unit =
      testQuery(q,
                expectPartialPushdown = true,
                pushdown = false,
                expectSingleRead = expectSingleRead)

    it("select all users") { testNoPushdownQuery("select * from users") }
    it("select all movies") { testNoPushdownQuery("select * from movies") }
    it("select all reviews") { testNoPushdownQuery("select * from reviews") }
    it("basic filter") { testNoPushdownQuery("select * from users where id = 1") }
    it("basic agg") {
      testNoPushdownQuery("select floor(avg(age)) from users", expectSingleRead = true)
    }
    it("numeric order") {
      testNoPushdownQuery("select * from users order by id asc", expectSingleRead = true)
    }
    it("limit with sort") {
      testNoPushdownQuery("select * from users order by id limit 10", expectSingleRead = true)
    }
    it("implicit inner join") {
      testNoPushdownQuery("select * from users as a, reviews as b where a.id = b.user_id",
                          expectSingleRead = true)
    }
  }

  describe("sanity test the tables") {
    it("select all users") { testQuery("select * from users") }
    it("select all users (sampled)") { testQuery("select * from users_sample") }
    it("select all movies") { testQuery("select * from movies") }
    it("select all reviews") { testQuery("select * from reviews") }
  }

  describe("math expressions") {
    it("sinh") { testQuery("select sinh(rating) as sinh from reviews") }
    it("cosh") { testQuery("select cosh(rating) as cosh from reviews") }
    it("tanh") { testQuery("select tanh(rating) as tanh from reviews") }
    it("hypot") { testQuery("select hypot(rating, user_id) as hypot from reviews") }
    it("rint") { testQuery("select rint(rating) as rint from reviews") }
  }

  describe("datatypes") {
    // due to a bug in our dataframe comparison library we need to alias the column 4.9 to x...
    // this is because when the library asks spark for a column called "4.9", spark thinks the
    // library wants the table 4 and column 9.
    it("float literal") { testQuery("select 4.9 as x from movies") }

    it("negative float literal") { testQuery("select -24.345 as x from movies") }
    it("negative int literal") { testQuery("select -1 from users") }

    it("int") { testQuery("select id from users") }
    it("smallint") { testQuery("select age from users") }
    it("date") { testQuery("select birthday from users") }
    it("datetime") { testQuery("select created from reviews") }
    it("bool") { testQuery("select owns_house from users") }
    it("float") { testQuery("select critic_rating from movies") }
    it("text") { testQuery("select first_name from users") }
  }

  describe("filter") {
    it("numeric equality") { testQuery("select * from users where id = 1") }
    it("numeric inequality") { testQuery("select * from users where id != 1") }
    it("numeric comparison >") { testQuery("select * from users where id > 500") }
    it("numeric comparison > <") { testQuery("select * from users where id > 500 and id < 550") }
    it("string equality") { testQuery("select * from users where first_name = 'Evan'") }
  }

  describe("window functions") {
    it("rank order by") {
      testSingleReadQuery(
        "select out as a from (select rank() over (order by first_name) as out from users)")
    }
    it("rank partition order by") {
      testSingleReadQuery(
        "select rank() over (partition by first_name order by first_name) as out from users")
    }
    it("row_number order by") {
      testSingleReadQuery("select row_number() over (order by first_name) as out from users")
    }
    it("dense_rank order by") {
      testSingleReadQuery("select dense_rank() over (order by first_name) as out from users")
    }
    it("lag order by") {
      testSingleReadQuery(
        "select first_name, lag(first_name) over (order by first_name) as out from users")
    }
    it("lead order by") {
      testSingleReadQuery(
        "select first_name, lead(first_name) over (order by first_name) as out from users")
    }
    it("ntile(3) order by") {
      testSingleReadQuery(
        "select first_name, ntile(3) over (order by first_name) as out from users")
    }
    it("percent_rank order by") {
      testSingleReadQuery(
        "select first_name, percent_rank() over (order by first_name) as out from users")
    }
  }

  describe("sort/limit") {
    it("numeric order") { testOrderedQuery("select * from users order by id asc") }
    it("text order") {
      testOrderedQuery("select * from users order by first_name desc, last_name asc, id asc")
    }
    it("text order expression") {
      testOrderedQuery("select * from users order by `email` like '%@gmail%', id asc")
    }

    it("text order case") {
      testOrderedQuery(
        "select * from users where first_name in ('Abbey', 'a') order by first_name desc, id asc")
    }

    it("simple limit") { testOrderedQuery("select 'a' from users limit 10") }
    it("limit with sort") { testOrderedQuery("select * from users order by id limit 10") }
    it("limit with sort on inside") {
      testOrderedQuery("select * from (select * from users order by id) limit 10")
    }
    it("limit with sort on outside") {
      testOrderedQuery("select * from (select * from users order by id limit 10) order by id")
    }
  }

  describe("joins") {
    describe("successful pushdown") {
      it("implicit inner join") {
        testSingleReadQuery("select * from users as a, reviews where a.id = reviews.user_id")
      }
      it("explicit inner join") {
        testSingleReadQuery("select * from users inner join reviews on users.id = reviews.user_id")
      }
      it("cross join") {
        testSingleReadQuery("select * from users cross join reviews on users.id = reviews.user_id")
      }
      it("left outer join") {
        testSingleReadQuery(
          "select * from users left outer join reviews on users.id = reviews.user_id")
      }
      it("right outer join") {
        testSingleReadQuery(
          "select * from users right outer join reviews on users.id = reviews.user_id")
      }
      it("full outer join") {
        testSingleReadQuery(
          "select * from users full outer join reviews on users.id = reviews.user_id")
      }
      it("natural join") {
        testSingleReadQuery(
          "select users.id, rating from users natural join (select user_id as id, rating from reviews)")
      }
      it("complex join") {
        testSingleReadQuery(
          """
            |  select users.id, round(avg(rating), 2) as rating, count(*) as num_reviews
            |  from users inner join reviews on users.id = reviews.user_id
            | group by users.id
            |""".stripMargin)
      }
      it("inner join without condition") {
        testSingleReadQuery(
          "select * from users inner join reviews order by concat(users.id, ' ', reviews.user_id, ' ', reviews.movie_id) limit 10")
      }
      it("cross join without condition") {
        testSingleReadQuery(
          "select * from users cross join reviews order by concat(users.id, ' ', reviews.user_id, ' ', reviews.movie_id) limit 10")
      }
    }

    describe("unsuccessful pushdown") {
      describe("udf in the left relation") {
        it("explicit inner join") {
          testSingleReadQuery(
            "select * from (select rating, stringIdentity(user_id) as user_id from reviews) inner join users on users.id = user_id",
            expectPartialPushdown = true)
        }
        it("cross join") {
          testSingleReadQuery(
            "select * from (select rating, stringIdentity(user_id) as user_id from reviews) cross join users on users.id = user_id",
            expectPartialPushdown = true)
        }
        it("left outer join") {
          testSingleReadQuery(
            "select * from (select rating, stringIdentity(user_id) as user_id from reviews) left outer join users on users.id = user_id",
            expectPartialPushdown = true
          )
        }
        it("right outer join") {
          testSingleReadQuery(
            "select * from (select rating, stringIdentity(user_id) as user_id from reviews) right outer join users on users.id = user_id",
            expectPartialPushdown = true
          )
        }
        it("full outer join") {
          testSingleReadQuery(
            "select * from (select rating, stringIdentity(user_id) as user_id from reviews) full outer join users on users.id = user_id",
            expectPartialPushdown = true
          )
        }
      }

      describe("udf in the right relation") {
        it("explicit inner join") {
          testSingleReadQuery(
            "select * from users inner join (select rating, stringIdentity(user_id) as user_id from reviews) on users.id = user_id",
            expectPartialPushdown = true)
        }
        it("cross join") {
          testSingleReadQuery(
            "select * from users cross join (select rating, stringIdentity(user_id) as user_id from reviews) on users.id = user_id",
            expectPartialPushdown = true)
        }
        it("left outer join") {
          testSingleReadQuery(
            "select * from users left outer join (select rating, stringIdentity(user_id) as user_id from reviews) on users.id = user_id",
            expectPartialPushdown = true
          )
        }
        it("right outer join") {
          testSingleReadQuery(
            "select * from users right outer join (select rating, stringIdentity(user_id) as user_id from reviews) on users.id = user_id",
            expectPartialPushdown = true
          )
        }
        it("full outer join") {
          testSingleReadQuery(
            "select * from users full outer join (select rating, stringIdentity(user_id) as user_id from reviews) on users.id = user_id",
            expectPartialPushdown = true
          )
        }
      }

      describe("udf in the condition") {
        it("explicit inner join") {
          testSingleReadQuery(
            "select * from users inner join reviews on stringIdentity(users.id) = stringIdentity(reviews.user_id)",
            expectPartialPushdown = true)
        }
        it("cross join") {
          testSingleReadQuery(
            "select * from users cross join reviews on stringIdentity(users.id) = stringIdentity(reviews.user_id)",
            expectPartialPushdown = true)
        }
        it("left outer join") {
          testSingleReadQuery(
            "select * from users left outer join reviews on stringIdentity(users.id) = stringIdentity(reviews.user_id)",
            expectPartialPushdown = true)
        }
        it("right outer join") {
          testSingleReadQuery(
            "select * from users right outer join reviews on stringIdentity(users.id) = stringIdentity(reviews.user_id)",
            expectPartialPushdown = true)
        }
        it("full outer join") {
          testSingleReadQuery(
            "select * from users full outer join reviews on stringIdentity(users.id) = stringIdentity(reviews.user_id)",
            expectPartialPushdown = true)
        }
      }

      describe("outer joins with empty condition") {
        it("left outer join") {
          testQuery(
            "select * from users left outer join (select rating from reviews order by rating limit 10)",
            expectPartialPushdown = true)
        }
        it("right outer join") {
          testQuery(
            "select * from users right outer join (select rating from reviews order by rating limit 10)",
            expectPartialPushdown = true)
        }
        it("full outer join") {
          testQuery(
            "select * from users full outer join (select rating from reviews order by rating limit 10)",
            expectPartialPushdown = true)
        }
      }

      describe("different dml jdbc options") {
        def testPushdown(joinType: String): Unit = {
          val df1 =
            spark.read
              .format(DefaultSource.MEMSQL_SOURCE_NAME_SHORT)
              .options(Map("dmlEndpoint" -> "host1:1020,host2:1010"))
              .load("testdb.users")
          val df2 =
            spark.read
              .format(DefaultSource.MEMSQL_SOURCE_NAME_SHORT)
              .options(Map("dmlEndpoint" -> "host3:1020,host2:1010"))
              .load("testdb.reviews")

          val joinedDf = df1.join(df2, df1("id") === df2("user_id"), joinType)
          log.debug(joinedDf.queryExecution.optimizedPlan.toString())
          assert(
            joinedDf.queryExecution.optimizedPlan match {
              case SQLGen.Relation(_) => false
              case _                  => true
            },
            "Join of the relations with different jdbc connection options should not be pushed down"
          )
        }

        it("explicit inner join") {
          testPushdown("inner")
        }
        it("cross join") {
          testPushdown("cross")
        }
        it("left outer join") {
          testPushdown("leftouter")
        }
        it("right outer join") {
          testPushdown("rightouter")
        }
        it("full outer join") {
          testPushdown("fullouter")
        }
      }
    }
  }

  describe("same-name column selection") {
    it("join two tables which project the same column name") {
      testOrderedQuery(
        "select * from (select id from users) as a, (select id from movies) as b where a.id = b.id order by a.id")
    }
    it("select same columns twice via natural join") {
      testOrderedQuery("select * from users as a natural join users order by a.id")
    }
    it("select same column twice from table") {
      testQuery("select first_name, first_name from users", expectPartialPushdown = true)
    }
    it("select same column twice from table with aliases") {
      testOrderedQuery("select first_name as a, first_name as a from users order by id")
    }
    it("select same alias twice (different column) from table") {
      testOrderedQuery("select first_name as a, last_name as a from users order by id")
    }
    it("select same column twice in subquery") {
      testQuery("select * from (select first_name, first_name from users) as x",
                expectPartialPushdown = true)
    }
    it("select same column twice from subquery with aliases") {
      testOrderedQuery(
        "select * from (select first_name as a, first_name as a from users order by id) as x")
    }
  }

  describe("datetimeExpressions") {
    describe("DateAdd") {
      it("positive num_days") { testQuery("select date_add(birthday, age) from users") }
      it("negative num_days") { testQuery("select date_add(birthday, -age) from users") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select date_add(stringIdentity(birthday), age) from users",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select date_add(birthday, -stringIdentity(age)) from users",
                  expectPartialPushdown = true)
      }
    }

    describe("DateSub") {
      it("positive num_days") { testQuery("select date_sub(birthday, age) from users") }
      it("negative num_days") { testQuery("select date_sub(birthday, -age) from users") }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select date_sub(stringIdentity(birthday), age) from users",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select date_sub(birthday, -stringIdentity(age)) from users",
                  expectPartialPushdown = true)
      }
    }

    val intervals = List(
      "1 month",
      "3 week",
      "2 day",
      "7 hour",
      "3 minute",
      "5 second",
      "1 month 1 week",
      "2 month 2 hour",
      "3 month 1 week 3 hour 5 minute 4 seconds"
    )

    describe("toUnixTimestamp") {
      it("works with TimestampType") {
        testQuery("select created, to_unix_timestamp(created) from reviews")
      }
      it("works with DateType") {
        testQuery("select birthday, to_unix_timestamp(birthday) from users")
      }
      it("partial pushdown because of udf") {
        testQuery("select to_unix_timestamp(stringIdentity(birthday)) from users",
                  expectPartialPushdown = true)
      }
    }

    describe("unixTimestamp") {
      it("works with TimestampType") {
        testQuery("select created, unix_timestamp(created) from reviews")
      }
      it("works with DateType") {
        testQuery("select birthday, unix_timestamp(birthday) from users")
      }
      it("partial pushdown because of udf") {
        testQuery("select unix_timestamp(stringIdentity(birthday)) from users",
                  expectPartialPushdown = true)
      }
    }

    describe("fromUnixTime") {
      it("works") {
        // cast is needed because in MemSQL 6.8 FROM_UNIXTIME query returns a result with microseconds
        testQuery("select id, cast(from_unixtime(id) as timestamp) from movies")
      }
      it("tutu") {
        testQuery("select from_unixtime(stringIdentity(id)) from movies",
                  expectPartialPushdown = true)
      }
    }

    // MemSQL and Spark differ on how they do last day calculations, so we ignore
    // them in some of these tests

    describe("timeAdd") {
      it("works") {
        for (interval <- intervals) {
          println(s"testing timeAdd with interval $interval")
          testQuery(s"""
                       | select created, created + interval $interval
                       | from reviews
                       | where date(created) != last_day(created)
                       |""".stripMargin)
        }
      }
      it("partial pushdown because of udf in the left argument") {
        testQuery(
          s"""
                     | select created, stringIdentity(created) + interval 1 day
                     | from reviews
                     | where date(created) != last_day(created)
                     |""".stripMargin,
          expectPartialPushdown = true
        )
      }
    }

    describe("timeSub") {
      it("works") {
        for (interval <- intervals) {
          println(s"testing timeSub with interval $interval")
          testQuery(s"""
                       | select created, created - interval $interval
                       | from reviews
                       | where date(created) != last_day(created)
                       |""".stripMargin)
        }
      }
      it("partial pushdown because of udf in the left argument") {
        testQuery(
          s"""
             | select created, stringIdentity(created) - interval 1 day
             | from reviews
             | where date(created) != last_day(created)
             |""".stripMargin,
          expectPartialPushdown = true
        )
      }
    }

    describe("addMonths") {
      it("works") {
        val numMonthsList = List(0, 1, 2, 12, 13, 200, -1, -2, -12, -13, -200)
        for (numMonths <- numMonthsList) {
          println(s"testing addMonths with $numMonths months")
          testQuery(s"""
                       | select created, add_months(created, $numMonths)
                       | from reviews
                       | where date(created) != last_day(created)
                       |""".stripMargin)
        }
      }
      it("partial pushdown with udf in the left argument") {
        testQuery(
          s"""
                     | select created, add_months(stringIdentity(created), 1)
                     | from reviews
                     | where date(created) != last_day(created)
                     |""".stripMargin,
          expectPartialPushdown = true
        )
      }
      it("partial pushdown with udf in the right argument") {
        testQuery(
          s"""
             | select created, add_months(created, stringIdentity(1))
             | from reviews
             | where date(created) != last_day(created)
             |""".stripMargin,
          expectPartialPushdown = true
        )
      }
    }

    describe("NextDay") {
      it("works") {
        for ((dayOfWeek, _) <- com.memsql.spark.ExpressionGen.DAYS_OF_WEEK_OFFSET_MAP) {
          println(s"testing nextDay with $dayOfWeek")
          testQuery(s"""
                       | select created, next_day(created, '$dayOfWeek')
                       | from reviews
                       |""".stripMargin)
        }
      }
      it("works with invalid day name") {
        testQuery(s"""
                     | select created, next_day(created, 'invalid_day')
                     | from reviews
                     |""".stripMargin)
      }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select next_day(stringIdentity(created), 'monday') from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select next_day(created, stringIdentity('monday')) from reviews",
                  expectPartialPushdown = true)
      }
    }

    describe("DateDiff") {
      it("works") {
        testSingleReadQuery(
          """
            | select birthday, created, DateDiff(birthday, created), DateDiff(created, birthday), DateDiff(created, created)
            | from users inner join reviews on users.id = reviews.user_id
            | """.stripMargin)
      }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select DateDiff(stringIdentity(created), created) from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select DateDiff(created, stringIdentity(created)) from reviews",
                  expectPartialPushdown = true)
      }
    }

    // Spark doesn't support explicit time intervals like `+/-hh:mm`

    val timeZones = List(
      "US/Mountain",
      "Asia/Seoul",
      "UTC",
      "EST",
      "Etc/GMT-6"
    )

    describe("fromUTCTimestamp") {
      it("works") {
        for (timeZone <- timeZones) {
          println(s"testing fromUTCTimestamp with timezone $timeZone")
          testQuery(s"select from_utc_timestamp(created, '$timeZone') from reviews")
        }
      }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select from_utc_timestamp(stringIdentity(created), 'EST') from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select from_utc_timestamp(created, stringIdentity('EST')) from reviews",
                  expectPartialPushdown = true)
      }
    }

    describe("toUTCTimestamp") {
      it("works") {
        for (timeZone <- timeZones) {
          println(s"testing toUTCTimestamp with timezone $timeZone")
          testQuery(s"select to_utc_timestamp(created, '$timeZone') from reviews")
        }
      }
      it("partial pushdown because of udf in the left argument") {
        testQuery("select to_utc_timestamp(stringIdentity(created), 'EST') from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery("select to_utc_timestamp(created, stringIdentity('EST')) from reviews",
                  expectPartialPushdown = true)
      }
    }

    // TruncTimestamp is called as date_trunc() in Spark
    describe("truncTimestamp") {
      it("works") {
        val dateParts = List(
          "YEAR",
          "YYYY",
          "YY",
          "MON",
          "MONTH",
          "MM",
          "DAY",
          "DD",
          "HOUR",
          "MINUTE",
          "SECOND",
          "WEEK",
          "QUARTER"
        )
        for (datePart <- dateParts) {
          println(s"testing truncTimestamp with datepart $datePart")
          testQuery(s"select date_trunc('$datePart', created) from reviews")
        }
      }
      it("partial pushdown because of udf in the left argument") {
        testQuery(s"select date_trunc(stringIdentity('DAY'), created) from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery(s"select date_trunc('DAY', stringIdentity(created)) from reviews",
                  expectPartialPushdown = true)
      }
    }

    // TruncDate is called as trunc()
    describe("truncDate") {
      it("works") {
        val dateParts = List("YEAR", "YYYY", "YY", "MON", "MONTH", "MM")
        for (datePart <- dateParts) {
          println(s"testing truncDate with datepart $datePart")
          testQuery(s"select trunc(created, '$datePart') from reviews")
        }
      }
      it("partial pushdown because of udf in the left argument") {
        testQuery(s"select trunc(stringIdentity(created), 'MONTH') from reviews",
                  expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery(s"select trunc(created, stringIdentity('MONTH')) from reviews",
                  expectPartialPushdown = true)
      }
    }

    describe("monthsBetween") {
      it("works") {
        for (interval <- intervals) {
          println(s"testing monthsBetween with interval $interval")
          testQuery(
            s"select months_between(created, created + interval $interval) from reviews"
          )
        }
      }
      it("partial pushdown because of udf in the left argument") {
        testQuery(
          s"select months_between(stringIdentity(created), created + interval 1 month) from reviews",
          expectPartialPushdown = true)
      }
      it("partial pushdown because of udf in the right argument") {
        testQuery(
          s"select months_between(created, stringIdentity(created) + interval 1 month) from reviews",
          expectPartialPushdown = true)
      }
    }
  }

  describe("partial pushdown") {
    it("ignores spark UDFs") {
      spark.udf.register("myUpper", (s: String) => s.toUpperCase)
      testQuery("select myUpper(first_name), id from users where id in (10,11,12)",
                expectPartialPushdown = true)
    }

    it("join with pure-jdbc relation") {
      testSingleReadQuery(
        """
        | select users.id, concat(first(users.first_name), " ", first(users.last_name)) as full_name
        | from users
        | inner join testdb_jdbc.reviews on users.id = reviews.user_id
        | group by users.id
        | """.stripMargin,
        expectPartialPushdown = true
      )
    }
  }

  describe("stringExpressions") {
    describe("StringTrim") {
      it("works") {
        testQuery("select id, trim(first_name) from users")
      }
      it("works when trimStr is ' '") {
        testQuery("select id, trim(both ' ' from first_name) from users")
      }
      it("partial pushdown when trimStr is not None and not ' '") {
        testQuery("select id, trim(both 'abc' from first_name) from users",
                  expectPartialPushdown = true)
      }
      it("partial pushdown with udf") {
        testQuery("select id, trim(stringIdentity(first_name)) from users",
                  expectPartialPushdown = true)
      }
    }

    describe("StringTrimLeft") {
      it("works") {
        testQuery("select id, ltrim(first_name) from users")
      }
      it("works when trimStr is ' '") {
        testQuery("select id, trim(leading ' ' from first_name) from users")
      }
      it("works when trimStr is ' ' (other syntax)") {
        testQuery("select id, ltrim(' ', first_name) from users")
      }
      it("partial pushdown when trimStr is not None and not ' '") {
        testQuery("select id, ltrim('abc', first_name) from users", expectPartialPushdown = true)
      }
      it("partial pushdown with udf") {
        testQuery("select id, ltrim(stringIdentity(first_name)) from users",
                  expectPartialPushdown = true)
      }
    }

    describe("StringTrimRight") {
      it("works") {
        testQuery("select id, rtrim(first_name) from users")
      }
      it("works when trimStr is ' '") {
        testQuery("select id, trim(trailing ' ' from first_name) from users")
      }
      it("works when trimStr is ' ' (other syntax)") {
        testQuery("select id, rtrim(' ', first_name) from users")
      }
      it("partial pushdown when trimStr is not None and not ' '") {
        testQuery("select id, rtrim('abc', first_name) from users", expectPartialPushdown = true)
      }
      it("partial pushdown with udf") {
        testQuery("select id, rtrim(stringIdentity(first_name)) from users",
                  expectPartialPushdown = true)
      }
    }
  }

  describe("decimalExpressions") {
    it("sum of decimals") {
      // If precision + 10 <= Decimal.MAX_LONG_DIGITS then DecimalAggregates optimizer will add MakeDecimal and UnscaledValue to this query
      for (precision <- 0 to Decimal.MAX_LONG_DIGITS - 10;
           // If rating >= 10^(precision - scale) then rating will overflow during the casting
           // JDBC returns null on overflow if !ansiEnabled and errors otherwise
           // MemSQL truncates the value on overflow
           // Because of this, we skip the case when scale is equals to precision (all rating values are less then 10)
           scale <- 1 until precision) {
        testSingleReadQuery(
          s"select sum(cast(rating as decimal($precision, $scale))) as rs from reviews")
      }
    }

    it("window expression with sum of decimals") {
      // If precision + 10 <= Decimal.MAX_LONG_DIGITS then DecimalAggregates optimizer will add MakeDecimal and UnscaledValue to this query
      for (precision <- 1 to Decimal.MAX_LONG_DIGITS - 10;
           // If rating >= 10^(precision - scale) then rating will overflow during the casting
           // JDBC returns null on overflow if !ansiEnabled and errors otherwise
           // MemSQL truncates the value on overflow
           // Because of this, we skip the case when scale is equals to precision (all rating values are less then 10)
           scale <- 1 until precision) {
        testSingleReadQuery(
          s"select sum(cast(rating as decimal($precision, $scale))) over (order by rating) as out from reviews")
      }
    }

    it("avg of decimals") {
      // If precision + 4 <= MAX_DOUBLE_DIGITS (15) then DecimalAggregates optimizer will add MakeDecimal and UnscaledValue to this query
      for (precision <- 1 to 11;
           // If rating >= 10^(precision - scale) then rating will overflow during the casting
           // JDBC returns null on overflow if !ansiEnabled and errors otherwise
           // MemSQL truncates the value on overflow
           // Because of this, we skip the case when scale is equals to precision (all rating values are less then 10)
           scale <- 1 until precision) {
        testSingleReadQuery(
          s"select avg(cast(rating as decimal($precision, $scale))) as rs from reviews")
      }
    }

    it("window expression with avg of decimals") {
      // If precision + 4 <= MAX_DOUBLE_DIGITS (15) then DecimalAggregates optimizer will add MakeDecimal and UnscaledValue to this query
      for (precision <- 1 to 11;
           // If rating >= 10^(precision - scale) then rating will overflow during the casting
           // JDBC returns null on overflow if !ansiEnabled and errors otherwise
           // MemSQL truncates the value on overflow
           // Because of this, we skip the case when scale is equals to precision (all rating values are less then 10)
           scale <- 1 until precision) {
        testSingleReadQuery(
          s"select avg(cast(rating as decimal($precision, $scale))) over (order by rating) as out from reviews")
      }
    }
  }

  describe("hash") {
    describe("sha2") {
      val supportedBitLengths = List(256, 384, 512, 100, -100, 1234)
      it("short literal") {
        for (bitLength <- supportedBitLengths) {
          testQuery(s"select sha2(first_name, ${bitLength}S) from users")
        }
      }
      it("int literal") {
        for (bitLength <- supportedBitLengths) {
          testQuery(s"select sha2(first_name, ${bitLength}) from users")
        }
      }
      it("long literal") {
        for (bitLength <- supportedBitLengths) {
          testQuery(s"select sha2(first_name, ${bitLength}L) from users")
        }
      }
      it("foldable expression") {
        for (bitLength <- supportedBitLengths) {
          testQuery(s"select sha2(first_name, ${bitLength} + 256) from users")
        }
      }
      describe("partial pushdown when bitLength is 224 (it is not supported by MemSQL)") {
        it("short") {
          testQuery(s"select sha2(first_name, 224S) from users", expectPartialPushdown = true)
        }
        it("int") {
          testQuery(s"select sha2(first_name, 224) from users", expectPartialPushdown = true)
        }
        it("long") {
          testQuery(s"select sha2(first_name, 224L) from users", expectPartialPushdown = true)
        }
      }
      it("partial pushdown when left argument contains udf") {
        testQuery("select sha2(stringIdentity(first_name), 224) from users",
                  expectPartialPushdown = true)
      }
      it("partial pushdown when right argument is not a numeric") {
        testQuery("select sha2(first_name, '224') from users", expectPartialPushdown = true)
      }
    }
  }
}
