package com.flowops.architecture;

import com.flowops.domain.mapper.task.TaskMapper;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * 架构守护测试（docs/06 §6.2 防绕过的工程手段）。
 * <b>规则用测试守住，不靠 code review</b> —— 违反架构约定的代码在这里直接失败。
 */
@AnalyzeClasses(packages = "com.flowops", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * D-12 防绕过（docs/06 §6.2 ①）：TaskMapper 的访问只允许收敛在
     * server 的 task.service 包（提交/干预/查询）与 domain 的 service 包
     * （TaskCreationService —— M4-S3 起的跨模块落库唯一入口，server 提交与
     * scheduler 回填投放共用，docs/06 §6.2 "定时触发与回填也调 createTask"）。
     * 新代码要触碰 TaskMapper，必须先想清楚它是否属于创建/查询链路——这条规则逼着开发者做这个决定。
     * （insert 继承自 BaseMapper，按调用点断言不可靠，故用"依赖收敛"这一更强的等价约束。）
     */
    @ArchTest
    static final ArchRule taskMapperAccessIsConverged =
            classes().that().areAssignableTo(TaskMapper.class)
                    .should().onlyHaveDependentClassesThat()
                    .resideInAnyPackage("com.flowops.modules.task.service..", "com.flowops.domain.service..")
                    .because("D-12：task 的写入必须收敛到 TaskCreationService（domain 落库唯一入口）"
                            + "与 task.service 的触发路径（docs/06 §6.2），禁止任何路径直插 task");

    /**
     * 分层（docs/03 §2.1）：controller → service → mapper，禁止越层与逆向依赖。
     * （consideringOnlyDependenciesInLayers：非分层类——横切切面/配置/领域守卫——不参与判定。）
     */
    @ArchTest
    static final ArchRule layered =
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                    .layer("Controller").definedBy("com.flowops.modules..controller..")
                    .layer("Service").definedBy("com.flowops.modules..service..")
                    .layer("Mapper").definedBy("com.flowops.domain.mapper..")
                    .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                    .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
                    .whereLayer("Mapper").mayOnlyBeAccessedByLayers("Service");

    /**
     * D-08：server 不得依赖 scheduler 的任何类（两者只通过 DB 状态 + Redis 队列协作）。
     * 一期靠 Maven 模块隔离即可保证；本规则是防未来有人"顺手加依赖"的守门员。
     */
    @ArchTest
    static final ArchRule serverMustNotDependOnScheduler =
            noClasses().that().resideInAPackage("com.flowops..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.flowops.scheduler..")
                    .because("D-08：server 与 scheduler 互不依赖（docs/03 §1.2 铁律）");
}
