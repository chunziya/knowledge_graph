package processing;

import com.google.protobuf.Message;
import database.MysqlDatabase;
import database.Neo4jDatabase;
import org.apache.http.util.TextUtils;
import org.apache.log4j.Logger;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;

public class Mysql2Neo4jThread {
    private static Logger logger = Logger.getLogger(MysqlToNeo4j.class);

    public static void main(String[] args) throws SQLException {
        final Connection mysqlConnection = MysqlDatabase.getConnection();
        Session sessionAll = Neo4jDatabase.getSession();

        /*
            获得表格总行数，计算循环次数
         */
        ResultSet result = mysqlConnection.createStatement().executeQuery("SELECT count(*) as count FROM ArticleInfo_2010");
        result.next();
        int row = result.getInt("count");
        int allRounds = (int) (row / 1000);
        logger.info("获取到 " + row + " 条数据");
        /*
            初始化主键（ID）
         */
        final int[] authorId = {sessionAll.run("MATCH (o:czc_Author) RETURN COUNT(o) AS count").next().get("count").asInt()};
        final int[] paperId = {sessionAll.run("MATCH (o:czc_Paper) RETURN COUNT(o) AS count").next().get("count").asInt()};
        final int[] orgId = {sessionAll.run("MATCH (o:czc_Organ) RETURN COUNT(o) AS count").next().get("count").asInt()};
        final int[] journalId = {sessionAll.run("MATCH (o:czc_Journal) RETURN COUNT(o) AS count").next().get("count").asInt()};
        final int[] keywordId = {sessionAll.run("MATCH (o:czc_Keyword) RETURN COUNT(o) AS count").next().get("count").asInt()};
        sessionAll.close();
        /*
            每次循环处理1000条数据
         */
        ExecutorService executorService = Executors.newFixedThreadPool(100);
        for (int round = 0; round < allRounds; round++) {
            final int finalRound = round;
            executorService.submit(
                    new Runnable() {
                        public void run() {
                            int pId, aId, jId, oId, kId;
                            try {
                                Session session = Neo4jDatabase.getSession();
                                PreparedStatement mysqlPs = mysqlConnection.prepareStatement("SELECT * FROM ArticleInfo_2010 limit ?,1000");
                                mysqlPs.setInt(1, 1000 * finalRound);
                                ResultSet mResult = mysqlPs.executeQuery();
                                //依次从每行数据中提取实体、建立关系

                                try {
                                    while (mResult.next()) {
                                /*
                                    提取实体
                                 */
                                        String author = mResult.getString("Author");
                                        String paper = mResult.getString("Title").replace("\"", "\\\"");
                                        String org = mResult.getString("Organ").replace("\"", "\\\"");
                                        String year = mResult.getString("Year");
                                        String journal = mResult.getString("JournalName");
                                        String keyword = mResult.getString("Keyword");
                                        String authorOrgan = mResult.getString("AuthorOrgan");

                                        //添加paper实体
                                        if (TextUtils.isEmpty(paper)) {
                                            continue;
                                        }
                                        //查询paper实体是否已经在图数据库中
                                        StatementResult paperResult = session.run("MATCH (p:czc_Paper) WHERE p.paper_name =\"" + paper.trim() + "\" RETURN p");
                                        //如果存在则跳过当前行数据
                                        if (paperResult.hasNext()) {
                                            continue;
                                        }
                                        //检查作者是否为空
                                        HashMap<String, Integer> authorMap = new HashMap<String, Integer>();
                                        if (!TextUtils.isEmpty(author)) {
                                            synchronized (this) {
                                                pId = (++paperId[0]);
                                                System.out.println("第" + finalRound + "轮, paperId: " + pId);
                                            }
                                            //添加paper实体
                                            session.run("CREATE (p:czc_Paper {paperId: " + pId + ", paper_name: \"" + paper.trim()
                                                    + "\", paper_class: \"" + mResult.getString("Class") + "\"})");
                                            //年份不为空时
                                            if (!TextUtils.isEmpty(year)) {
                                                //添加paper-year关系
                                                session.run("MATCH (p:czc_Paper),(y:czc_Year) WHERE p.paperId =" + pId
                                                        + " AND y.year =\"" + year.trim() + "\" CREATE (p)-[r:TIME]->(y)");
                                            }

                                            //添加author实体
                                            //使用HashMap记录<Author:ID>
                                            String[] authorArray = author.split(";");
                                            for (int i = 0; i < authorArray.length; i++) {
                                                String authorName = authorArray[i].trim();
                                                if (!TextUtils.isEmpty(authorName)) {  //判空（必要）
                                                    synchronized (this) {
                                                        aId = (++authorId[0]);
//                                                        System.out.println("第" + finalRound + "轮,authorId: " + aId);
                                                    }
                                                    session.run("CREATE (p:czc_Author {authorId: " + aId
                                                            + ", author_name: \"" + authorName + "\"})");
                                                    authorMap.put(authorName, aId);
                                                    //添加author-paper关系
                                                    session.run("MATCH (a:czc_Author),(p:czc_Paper) WHERE a.authorId =" +
                                                            aId + " AND p.paperId =" + pId
                                                            + " CREATE (a)-[r:WRITE]->(p)");
                                                }
                                            }
                                        } else {
                                            //System.out.println("缺少作者的论文：" + paper);
                                            continue;
                                        }

                                        //添加organ实体
                                        if (!TextUtils.isEmpty(org)) {  //判空
                                            String[] orgArray = org.split(";");
                                            for (int i = 0; i < orgArray.length; i++) {
                                                String orgName = orgArray[i].trim();
                                                //查询organization实体是否已经存在
                                                StatementResult orgResult = session.run("MATCH (o:czc_Organ) WHERE o.org_name = \""
                                                        + orgName + "\" RETURN o");
                                                //不存在则添加organization实体
                                                if (!orgResult.hasNext()) {  //去重
                                                    synchronized (this) {
                                                        oId = (++orgId[0]);
//                                                        System.out.println("第" + finalRound + "轮, organId: " + oId);
                                                    }
                                                    session.run("CREATE (o:czc_Organ {orgId: " + oId
                                                            + ", org_name: \"" + orgName + "\"})");
                                                }
                                            }
                                        }

                                        //添加journal实体
                                        if (!TextUtils.isEmpty(journal)) {  //判空
                                            StatementResult journalResult = session.run("MATCH (j:czc_Journal) WHERE j.journal_name = \""
                                                    + journal.trim() + "\" RETURN j");
                                            if (!journalResult.hasNext()) {  //去重
                                                synchronized (this) {
                                                    jId = (++journalId[0]);
//                                                    System.out.println("第" + finalRound + "轮, journalId: " + jId);
                                                }
                                                session.run("CREATE (j:czc_Journal {journalId: " + jId
                                                        + ", journal_name: \"" + journal.trim() + "\"})");
                                            }
                                            //添加paper-journal关系
                                            session.run("MATCH (p:czc_Paper),(j:czc_Journal) WHERE p.paperId =" +
                                                    pId + " AND j.journal_name =\"" + journal.trim()
                                                    + "\" CREATE (p)-[r:BELONG]->(j)");
                                        }

                                        //添加keyword实体
                                        if (!TextUtils.isEmpty(keyword)) {  //判空
                                            String[] keywordArray = keyword.split(";");
                                            for (int i = 0; i < keywordArray.length; i++) {
                                                if (!TextUtils.isEmpty(keywordArray[i].trim())) {  //判空
                                                    StatementResult keyword_result = session.run("MATCH (k:czc_Keyword) WHERE k.keyword_name = \"" +
                                                            keywordArray[i].trim() + "\" RETURN k");
                                                    if (!keyword_result.hasNext()) {  //去重
                                                        synchronized (this) {
                                                            kId = (++keywordId[0]);
//                                                            System.out.println("第" + finalRound + "轮, keywordId: " + kId);
                                                        }
                                                        session.run("CREATE (k:czc_Keyword {keywordId: " + kId
                                                                + ", keyword_name: \"" + keywordArray[i].trim() + "\"})");
                                                    }
                                                    //添加paper-keyword关系
                                                    session.run("MATCH (p:czc_Paper),(k:czc_Keyword) WHERE p.paperId =" + pId +
                                                            " AND k.keyword_name =\"" + keywordArray[i].trim()
                                                            + "\" CREATE (p)-[r:INVOLVED]->(k)");
                                                }
                                            }
                                        }

                                        //添加author-organ关系
                                        //从HashMap中取作者ID，与Organ建立关系
                                        if (!TextUtils.isEmpty(authorOrgan)) {
                                            String a = null;
                                            String o = null;
                                            String[] aoArray = authorOrgan.split(";");
                                            for (int i = 0; i < aoArray.length; i++) {
                                                if (aoArray[i].contains("[")) {
                                                    a = aoArray[i].split("\\[")[0].trim();
                                                    o = aoArray[i].split("\\[")[1].trim()
                                                            .replace("]", "")
                                                            .replace("\"", "\\\"");
                                                    session.run("MATCH (a:czc_Author),(o:czc_Organ) WHERE a.authorId = " +
                                                            authorMap.get(a) + " AND o.org_name = \"" + o
                                                            + "\" CREATE (a)-[r:BELONG]->(o)");
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.warn("处理数据时出错");
                                }
                                session.close();
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }
                        }
                    });
        }
    }
}

