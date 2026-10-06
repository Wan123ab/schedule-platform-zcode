/* ============================================================
   FlowOps 原型 · Mock 数据 V0.2（单一数据源，各页面共享）
   口径与 PRD V0.2 示例对齐；每段标注「真源替换点」便于工程替换
   ============================================================ */
(function () {
  var TODAY = '2026-09-25';

  var roles = {
    PLATFORM_ADMIN:      { name: '平台管理员', user: 'admin' },
    OPS:                 { name: '运维人员',   user: 'ops001' },
    PROJECT_ADMIN:       { name: '项目管理员', user: 'pm-ecom' },
    OPERATOR_MAINTAINER: { name: '算子维护者', user: 'dev-lin' },
    BUSINESS_USER:       { name: '业务人员',   user: 'biz-wang' },
    AUDITOR:             { name: '只读/审计',  user: 'audit01' }
  };

  /* 47 权限点（07 §5.2）按角色的演示子集：按钮显隐 + 数据行过滤共用 */
  var PERM_DEMO = {
    PLATFORM_ADMIN: ['*'],
    OPS: ['schedule:cluster:write','schedule:node:write','schedule:node:test','schedule:queue:write',
          'schedule:credential:write','schedule:credential:rotate','schedule:task:stop','schedule:task:enqueue_front',
          'schedule:task:retry','schedule:alert:write','schedule:platform:health','schedule:workflow:read','schedule:operator:read',
          'schedule:openapi:read','opsView'],
    PROJECT_ADMIN: ['schedule:openapi:write','schedule:openapi:read','schedule:project:write','schedule:project:member','schedule:operator:write','schedule:operator:delete',
          'schedule:operator:publish','schedule:workflow:write','schedule:workflow:publish','schedule:workflow:execute',
          'schedule:trigger:write','schedule:task:stop','schedule:task:retry','schedule:backfill:write','schedule:alert:write'],
    OPERATOR_MAINTAINER: ['schedule:operator:write','schedule:operator:publish','schedule:operator:dryrun',
          'schedule:workflow:write','schedule:workflow:execute','schedule:workflow:read','schedule:operator:read'],
    BUSINESS_USER: ['schedule:workflow:write','schedule:workflow:execute','schedule:workflow:read','schedule:operator:read',
          'schedule:task:stop','schedule:task:retry'],
    AUDITOR: []
  };
  /* DataScope 演示：任务列表按角色过滤项目（D-19 双要素的可见半边） */
  var SCOPE_DEMO = {
    PLATFORM_ADMIN: 'ALL', OPS: 'AUTHORIZED_CLUSTER', AUDITOR: 'ALL',
    PROJECT_ADMIN: ['PRJ-0001'], OPERATOR_MAINTAINER: ['PRJ-0001'], BUSINESS_USER: ['PRJ-0001']
  };

  /* ============ 域 0：项目 ============ 真源替换点：GET /api/projects */
  var projects = [
    { id: 'PRJ-0001', name: '电商数据平台', tenant: '默认租户', owner: '陈晓明', members: 8,
      clusters: ['CL-0001', 'CL-0003'], maxConcurrent: 5, maxWaiting: 50, status: 'ENABLED',
      workflows: 12, tasks: 3841, desc: '订单/商品/流量域的日常批处理与数据同步' },
    { id: 'PRJ-0002', name: '风控实时计算', tenant: '默认租户', owner: '刘敏', members: 5,
      clusters: ['CL-0001', 'CL-0002'], maxConcurrent: 8, maxWaiting: 100, status: 'ENABLED',
      workflows: 6, tasks: 2210, desc: '实时特征与风控规则计算任务' },
    { id: 'PRJ-0003', name: '算法训练平台', tenant: '默认租户', owner: '赵磊', members: 6,
      clusters: ['CL-0002'], maxConcurrent: 4, maxWaiting: 30, status: 'ENABLED',
      workflows: 4, tasks: 486, desc: '离线训练与模型评估任务' },
    { id: 'PRJ-0004', name: '供应链数据平台', tenant: '默认租户', owner: '孙倩', members: 3,
      clusters: ['CL-0004'], maxConcurrent: 5, maxWaiting: 50, status: 'DISABLED',
      workflows: 2, tasks: 96, desc: '供应链计划批处理，已停用' }
  ];

  /* ============ 域 1：集群 / 节点 / 队列 ============ 真源替换点：GET /api/clusters */
  var clusters = [
    { id: 'CL-0001', name: 'prod-compute-01', env: '生产', status: 'PARTIAL_ABNORMAL',
      nodes: 12, online: 10, offline: 2, idle: 3,
      cpu: { total: 384, used: 221 }, gpu: { total: 16, used: 9 },
      mem: { total: 983040, used: 557056 }, disk: { total: 49152, used: 21845 },
      running: 23, pending: 7, history: 5120, lastHb: '2026-09-25 15:32:48' },
    { id: 'CL-0002', name: 'gpu-train-cluster', env: '生产', status: 'NORMAL',
      nodes: 8, online: 8, offline: 0, idle: 0,
      cpu: { total: 256, used: 240 }, gpu: { total: 24, used: 21 },
      mem: { total: 786432, used: 724992 }, disk: { total: 32768, used: 11240 },
      running: 31, pending: 12, history: 3401, lastHb: '2026-09-25 15:32:50' },
    { id: 'CL-0003', name: 'prod-compute-02', env: '生产', status: 'NORMAL',
      nodes: 6, online: 6, offline: 0, idle: 1,
      cpu: { total: 192, used: 88 }, gpu: { total: 8, used: 3 },
      mem: { total: 491520, used: 210432 }, disk: { total: 24576, used: 8220 },
      running: 11, pending: 3, history: 2210, lastHb: '2026-09-25 15:32:49' },
    { id: 'CL-0004', name: 'dev-cluster', env: '开发', status: 'MAINTENANCE',
      nodes: 4, online: 4, offline: 0, idle: 4,
      cpu: { total: 64, used: 4 }, gpu: { total: 2, used: 0 },
      mem: { total: 131072, used: 8192 }, disk: { total: 8192, used: 1900 },
      running: 0, pending: 0, history: 812, lastHb: '2026-09-25 15:32:44' }
  ];

  var nodes = [
    { id: 'N-0101', name: 'prod-node-014', cluster: 'CL-0001', ip: '10.20.31.14', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0001', credFp: '****a91f', tags: ['etl', 'ssd'], status: 'ONLINE', hbMiss: 0,
      cpu: { total: 16, used: 9.2 }, gpu: { total: 0, used: 0 }, mem: { total: 65536, used: 38912 },
      disk: { total: 4194304, used: 1258291 }, running: 3, lastHb: '2026-09-25 15:32:46', lastAlloc: '2026-09-25 15:12:03', maxSteps: 8 },
    { id: 'N-0102', name: 'prod-node-031', cluster: 'CL-0001', ip: '10.20.31.31', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0001', credFp: '****a91f', tags: ['etl', 'high-mem'], status: 'ONLINE', hbMiss: 0,
      cpu: { total: 16, used: 14.1 }, gpu: { total: 0, used: 0 }, mem: { total: 65536, used: 58000 },
      disk: { total: 4194304, used: 2100000 }, running: 4, lastHb: '2026-09-25 15:32:47', lastAlloc: '2026-09-25 15:30:11', maxSteps: 8 },
    { id: 'N-0103', name: 'prod-node-007', cluster: 'CL-0001', ip: '10.20.31.7', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0001', credFp: '****a91f', tags: ['etl-heavy'], status: 'ONLINE', hbMiss: 1,
      cpu: { total: 16, used: 12.4 }, gpu: { total: 0, used: 0 }, mem: { total: 65536, used: 49000 },
      disk: { total: 4194304, used: 1800000 }, running: 3, lastHb: '2026-09-25 15:32:24', lastAlloc: '2026-09-25 15:28:40', maxSteps: 8 },
    { id: 'N-0104', name: 'prod-node-019', cluster: 'CL-0001', ip: '10.20.31.19', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0001', credFp: '****a91f', tags: ['etl'], status: 'OFFLINE', hbMiss: 104,
      cpu: { total: 16, used: 0 }, gpu: { total: 0, used: 0 }, mem: { total: 65536, used: 0 },
      disk: { total: 4194304, used: 900000 }, running: 0, lastHb: '2026-09-25 15:06:12', lastAlloc: '2026-09-25 14:02:55', maxSteps: 8 },
    { id: 'N-0105', name: 'prod-node-022', cluster: 'CL-0001', ip: '10.20.31.22', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0001', credFp: '****a91f', tags: ['etl', 'ssd'], status: 'ONLINE', hbMiss: 0,
      cpu: { total: 16, used: 4.1 }, gpu: { total: 0, used: 0 }, mem: { total: 65536, used: 21000 },
      disk: { total: 4194304, used: 640000 }, running: 1, lastHb: '2026-09-25 15:32:45', lastAlloc: '2026-09-25 15:00:02', maxSteps: 8 },
    { id: 'N-0201', name: 'gpu-node-003', cluster: 'CL-0002', ip: '10.20.44.203', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0003', credFp: '****77a0', tags: ['gpu', 'cuda12'], status: 'ONLINE', hbMiss: 0,
      cpu: { total: 32, used: 24.2 }, gpu: { total: 4, used: 4 }, mem: { total: 131072, used: 102400 },
      disk: { total: 8388608, used: 3100000 }, running: 2, lastHb: '2026-09-25 15:32:48', lastAlloc: '2026-09-25 15:26:30', maxSteps: 4 },
    { id: 'N-0202', name: 'gpu-node-005', cluster: 'CL-0002', ip: '10.20.44.205', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0003', credFp: '****77a0', tags: ['gpu', 'cuda12', 'a100'], status: 'ONLINE', hbMiss: 0,
      cpu: { total: 32, used: 30.1 }, gpu: { total: 4, used: 4 }, mem: { total: 131072, used: 120000 },
      disk: { total: 8388608, used: 4200000 }, running: 3, lastHb: '2026-09-25 15:32:49', lastAlloc: '2026-09-25 15:31:02', maxSteps: 4 },
    { id: 'N-0203', name: 'gpu-node-008', cluster: 'CL-0002', ip: '10.20.44.208', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0003', credFp: '****77a0', tags: ['gpu'], status: 'ONLINE', hbMiss: 0,
      cpu: { total: 32, used: 8.2 }, gpu: { total: 4, used: 1 }, mem: { total: 131072, used: 40960 },
      disk: { total: 8388608, used: 1500000 }, running: 1, lastHb: '2026-09-25 15:32:47', lastAlloc: '2026-09-25 15:18:44', maxSteps: 4 },
    { id: 'N-0301', name: 'prod2-node-011', cluster: 'CL-0003', ip: '10.20.32.11', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0001', credFp: '****a91f', tags: ['etl'], status: 'ONLINE', hbMiss: 0,
      cpu: { total: 32, used: 12.8 }, gpu: { total: 0, used: 0 }, mem: { total: 131072, used: 62000 },
      disk: { total: 8388608, used: 2100000 }, running: 2, lastHb: '2026-09-25 15:32:46', lastAlloc: '2026-09-25 15:22:18', maxSteps: 8 },
    { id: 'N-0401', name: 'dev-node-001', cluster: 'CL-0004', ip: '10.20.99.1', os: 'LINUX', conn: 'SSH',
      cred: 'CR-0002', credFp: '****b2e1', tags: [], status: 'ONLINE', hbMiss: 0,
      cpu: { total: 16, used: 1.2 }, gpu: { total: 0, used: 0 }, mem: { total: 32768, used: 4096 },
      disk: { total: 2097152, used: 480000 }, running: 0, lastHb: '2026-09-25 15:32:44', lastAlloc: '2026-09-24 18:40:12', maxSteps: 4 }
  ];

  var queues = [
    { id: 'Q-0001', name: 'high-priority', cluster: 'CL-0001', status: 'ENABLED', maxConc: 6, maxWait: 50,
      prio: 20, allowJump: false, waitTimeout: 3600, waiting: 2, running: 6 },
    { id: 'Q-0002', name: 'default', cluster: 'CL-0001', status: 'ENABLED', maxConc: 3, maxWait: 50,
      prio: 0, allowJump: true, waitTimeout: 3600, waiting: 47, running: 3 },
    { id: 'Q-0003', name: 'gpu-queue', cluster: 'CL-0002', status: 'ENABLED', maxConc: 4, maxWait: 30,
      prio: 10, allowJump: false, waitTimeout: 7200, waiting: 7, running: 4 },
    { id: 'Q-0004', name: 'batch-heavy', cluster: 'CL-0003', status: 'ENABLED', maxConc: 4, maxWait: 100,
      prio: 5, allowJump: true, waitTimeout: 5400, waiting: 12, running: 4 },
    { id: 'Q-0005', name: 'dev-default', cluster: 'CL-0004', status: 'DISABLED', maxConc: 2, maxWait: 20,
      prio: 0, allowJump: true, waitTimeout: 1800, waiting: 0, running: 0 }
  ];

  /* ============ 域 2：凭据 ============ 真源替换点：GET /api/credentials */
  var credentials = [
    { id: 'CR-0001', name: '生产节点SSH密钥', type: 'SSH_KEY', scope: '平台级', project: null,
      fingerprint: '****a91f', refCount: 12, status: 'VALID', lastUsed: '2026-09-25 15:30:40',
      expireAt: null, lastRotated: '2026-09-10 10:00:00', creator: 'ops001', nodes: ['prod-node-007 / 014 / 019 / 022 / 031', 'prod2-node-011'] },
    { id: 'CR-0002', name: '测试环境账号密码', type: 'USER_PASSWORD', scope: '项目级', project: 'PRJ-0004',
      fingerprint: '****b2e1', refCount: 1, status: 'EXPIRING', lastUsed: '2026-09-20 18:02:11',
      expireAt: '2026-10-01 00:00:00', lastRotated: '2026-06-30 09:12:00', creator: 'sun-qian', nodes: ['dev-node-001'] },
    { id: 'CR-0003', name: 'GPU训练集群密钥', type: 'SSH_KEY', scope: '平台级', project: null,
      fingerprint: '****77a0', refCount: 3, status: 'VALID', lastUsed: '2026-09-25 15:31:02',
      expireAt: null, lastRotated: '2026-09-17 11:00:00', creator: 'ops001', nodes: ['gpu-node-003 / 005 / 008'] },
    { id: 'CR-0004', name: '旧生产密钥（轮换保留期）', type: 'SSH_KEY', scope: '平台级', project: null,
      fingerprint: '****55d1', refCount: 0, status: 'VALID', lastUsed: '2026-09-17 10:59:00',
      expireAt: null, lastRotated: '2026-09-10 10:00:00', creator: 'ops001', nodes: [] }
  ];

  /* ============ 域 3：算子与版本 ============ 真源替换点：GET /api/operators */
  var operators = [
    { id: 'OP-0001',
      name: '订单数据抽取', cat: '数据接入', type: 'SHELL', project: 'PRJ-0001', status: 'ENABLED',
      latest: 'v1', versions: 1, creator: 'dev-lin', updated: '2026-09-01 10:20:00',
      desc: '从 Hive ODS 层按业务日期抽取订单明细' },
    { id: 'OP-0002',
      name: '数据质量校验', cat: '数据处理', type: 'PYTHON', project: 'PRJ-0001', status: 'ENABLED',
      latest: 'v2', versions: 2, creator: 'dev-lin', updated: '2026-09-12 09:30:00',
      desc: '必填/主键/时间范围 42 条规则校验' },
    { id: 'OP-0003',
      name: 'Spark数据清洗', cat: '数据处理', type: 'JAR', project: 'PRJ-0001', status: 'ENABLED',
      latest: 'v3', versions: 3, creator: 'dev-lin', updated: '2026-09-18 14:02:00',
      desc: '去重、空值填充、异常金额修正' },
    { id: 'OP-0004',
      name: '特征计算', cat: '计算分析', type: 'JAR', project: 'PRJ-0001', status: 'ENABLED',
      latest: 'v1', versions: 1, creator: 'dev-zhao', updated: '2026-09-05 16:40:00',
      desc: '用户/类目/时间滑窗三维特征工程' },
    { id: 'OP-0005',
      name: '模型评分', cat: '计算分析', type: 'PYTHON', project: 'PRJ-0003', status: 'ENABLED',
      latest: 'v1', versions: 1, creator: 'dev-zhao', updated: '2026-09-08 11:00:00',
      desc: 'LightGBM 批量评分，GPU 加速' },
    { id: 'OP-0006',
      name: '结果入库', cat: '输出交付', type: 'SHELL', project: 'PRJ-0001', status: 'ENABLED',
      latest: 'v1', versions: 1, creator: 'dev-lin', updated: '2026-08-28 15:20:00',
      desc: 'Sqoop 批量导出到 PostgreSQL DWS 层' },
    { id: 'OP-0007',
      name: '报告推送', cat: '输出交付', type: 'SHELL', project: 'PRJ-0001', status: 'ENABLED',
      latest: 'v1', versions: 1, creator: 'biz-wang', updated: '2026-08-22 11:00:00',
      desc: '生成日报并推送邮件组' },
    { id: 'OP-0008',
      name: '旧版库存导出工具', cat: '已停用', type: 'PYTHON', project: 'PRJ-0001', status: 'DISABLED',
      latest: 'v1', versions: 1, creator: 'dev-lin', updated: '2026-07-02 10:00:00',
      desc: '已被新版替代并停用（用于演示 DAG 校验规则 7）' }
  ];

  var opVersions = {
    'OP-0003': [
      { id: 'OPV-0003-03', op: 'OP-0003', no: 'v3', status: 'PUBLISHED', isDefault: true, publisher: 'dev-lin',
        publishedAt: '2026-09-18 14:02:00', file: 'spark-clean-3.1.0.jar', size: '48.2 MB', checksum: 'sha256:9f31c0…a2d4',
        os: 'LINUX', startCmd: '/usr/bin/spark-submit --master yarn --deploy-mode cluster --executor-memory ${execMem} --executor-cores ${execCores} --num-executors ${executors} --class com.ecom.etl.CleanJob /opt/ops/jars/spark-clean-3.1.0.jar --input ${inputPath} --output ${cleanedPath} --dedup-key ${dedupKey}',
        workDir: '/opt/flowops/work/${taskId}', env: [ { key: 'SPARK_HOME', value: '/opt/spark-3.5', secret: false }, { key: 'HADOOP_CONF_DIR', value: '/etc/hadoop/conf', secret: false } ],
        successCodes: [0], timeout: 7200, retry: 2,
        params: [
          { key: 'inputPath', name: '输入路径', type: 'TEXT', required: true, default: '', rule: 'HDFS 路径', help: '上游抽取产出的分区目录', overridable: false, sensitive: false },
          { key: 'cleanedPath', name: '清洗输出路径', type: 'TEXT', required: true, default: '/data/dwd/order_clean/dt=${bizDate}', rule: '', help: '', overridable: true, sensitive: false },
          { key: 'dedupKey', name: '去重主键', type: 'SINGLE', required: true, default: 'order_id', options: ['order_id', 'order_id+sku_id'], rule: '', help: '', overridable: true, sensitive: false },
          { key: 'execMem', name: 'Executor 内存', type: 'TEXT', required: false, default: '22g', rule: '', help: '', overridable: true, sensitive: false },
          { key: 'execCores', name: 'Executor 核数', type: 'NUMBER', required: false, default: '4', rule: '1~8', help: '', overridable: true, sensitive: false },
          { key: 'executors', name: 'Executor 数量', type: 'NUMBER', required: false, default: '4', rule: '1~20', help: '', overridable: true, sensitive: false }
        ],
        outputs: [
          { name: '清洗后文件路径', key: 'cleanedFilePath', type: '文件路径', mode: 'REGEX', expr: 'CLEAN_PATH=(\\S+)', required: true, example: '/data/dwd/order_clean/dt=2026-09-20' },
          { name: '清洗后行数', key: 'cleanedRows', type: '数字', mode: 'REGEX', expr: 'ROWS=(\\d+)', required: false, example: '1284729' }
        ] },
      { id: 'OPV-0003-02', op: 'OP-0003', no: 'v2', status: 'PUBLISHED', isDefault: false, publisher: 'dev-lin',
        publishedAt: '2026-08-30 11:00:00', file: 'spark-clean-3.0.2.jar', size: '47.1 MB', checksum: 'sha256:11aa02…77bc',
        os: 'LINUX', startCmd: '/usr/bin/spark-submit --master yarn --class com.ecom.etl.CleanJob /opt/ops/jars/spark-clean-3.0.2.jar --input ${inputPath}',
        workDir: '/opt/flowops/work/${taskId}', env: [], successCodes: [0], timeout: 7200, retry: 1,
        params: [ { key: 'inputPath', name: '输入路径', type: 'TEXT', required: true, default: '', rule: '', help: '', overridable: false, sensitive: false } ],
        outputs: [ { name: '清洗后文件路径', key: 'cleanedFilePath', type: '文件路径', mode: 'REGEX', expr: 'CLEAN_PATH=(\\S+)', required: true, example: '/data/tmp/out' } ] },
      { id: 'OPV-0003-01', op: 'OP-0003', no: 'v1', status: 'OFFLINE', isDefault: false, publisher: 'dev-lin',
        publishedAt: '2026-08-01 09:00:00', file: 'spark-clean-2.9.0.jar', size: '45.8 MB', checksum: 'sha256:6d20f1…90ee',
        os: 'LINUX', startCmd: '/usr/bin/spark-submit --master yarn --class com.ecom.etl.CleanJobV1 /opt/ops/jars/spark-clean-2.9.0.jar',
        workDir: '/opt/flowops/work/${taskId}', env: [], successCodes: [0], timeout: 3600, retry: 0,
        params: [], outputs: [] }
    ]
  };
  /* 通用版本数据（其余算子） */
  ['OP-0001', 'OP-0002', 'OP-0004', 'OP-0005', 'OP-0006', 'OP-0007', 'OP-0008'].forEach(function (oid) {
    var op = operators.filter(function (o) { return o.id === oid; })[0];
    opVersions[oid] = [{
      id: 'OPV-' + oid.slice(3) + '-01', op: oid, no: 'v1', status: 'PUBLISHED', isDefault: true, publisher: op.creator,
      publishedAt: op.updated, file: oid.toLowerCase() + '.py', size: '12.4 KB', checksum: 'sha256:3ac0…9e11',
      os: 'LINUX',
      startCmd: oid === 'OP-0005'
        ? '/opt/gpu/venv/bin/python /opt/ops/model/score.py --model ${modelPath} --input ${inputPath} --threshold ${threshold} --gpus ${gpuCount} --output ${scorePath}'
        : 'python3 main.py --date ${bizDate} --conf conf.yaml',
      workDir: '/opt/flowops/work/${taskId}', env: [], successCodes: [0], timeout: null, retry: null,
      params: [
        { key: 'inputPath', name: '输入路径', type: 'TEXT', required: oid !== 'OP-0001' && oid !== 'OP-0007', default: '', rule: '', help: '上游数据目录', overridable: false, sensitive: false },
        { key: 'bizDate', name: '业务日期', type: 'DATETIME', required: false, default: '${bizDate}', rule: '', help: '留空取平台变量', overridable: true, sensitive: false }
      ],
      outputs: oid === 'OP-0005'
        ? [ { name: '评分结果路径', key: 'scoreFilePath', type: '文件路径', mode: 'REGEX', expr: 'SCORE_PATH=(\\S+)', required: true, example: '/data/dws/order_score/dt=20260925' },
            { name: '模型 AUC', key: 'aucValue', type: '数字', mode: 'REGEX', expr: 'AUC=([\\d.]+)', required: false, example: '0.8471' } ]
        : oid === 'OP-0006'
          ? [ { name: '入库行数', key: 'loadedRows', type: '数字', mode: 'REGEX', expr: 'LOADED=(\\d+)', required: true, example: '1284729' } ]
          : [ { name: '输出文件路径', key: 'outputFilePath', type: '文件路径', mode: 'REGEX', expr: 'OUTPUT_PATH=(.*)', required: true, example: '/data/ecom/out/20260920' } ]
    }];
  });

  /* ============ 域 4：工作流（7 步菱形 DAG 主示例） ============ 真源替换点：GET /api/workflows */
  var wfSteps = [
    { id: 's1', name: '数据抽取', type: 'TASK', op: 'OP-0001', opVer: 'v1', x: 60, y: 210,
      params: { bizDate: '${bizDate}', sourceDb: 'hive://dw-prod:10000/ods.order' },
      res: { cpu: 4, gpu: 0, mem: 8192, disk: 10240 }, queue: 'high-priority',
      timeout: 3600, retry: 2, interval: 60, strategy: 'RETRY', mutex: '', tags: [] },
    { id: 's2', name: '数据校验', type: 'TASK', op: 'OP-0002', opVer: 'v2', x: 320, y: 80,
      params: { inputPath: '${step.数据抽取.output.outputFilePath}', rulesPath: '/data/ecom/rules/order_rules_v12.json', threshold: '0.998' },
      res: { cpu: 2, gpu: 0, mem: 4096, disk: 4096 }, queue: 'high-priority',
      timeout: 1800, retry: 1, interval: 60, strategy: 'RETRY', mutex: '', tags: [] },
    { id: 's3', name: '数据清洗', type: 'TASK', op: 'OP-0003', opVer: 'v3', x: 320, y: 330,
      params: { inputPath: '${step.数据抽取.output.outputFilePath}', cleanedPath: '/data/dwd/order_clean/dt=${bizDate}', dedupKey: 'order_id', execMem: '22g', execCores: 4, executors: 4 },
      res: { cpu: 16, gpu: 0, mem: 26624, disk: 20480 }, queue: 'high-priority',
      timeout: 7200, retry: 2, interval: 30, strategy: 'RETRY', mutex: 'orders_rw', tags: ['etl-heavy'] },
    { id: 's4', name: '特征计算', type: 'TASK', op: 'OP-0004', opVer: 'v1', x: 600, y: 210,
      params: { inputPath: '${step.数据清洗.output.cleanedFilePath}', featureDim: 286 },
      res: { cpu: 16, gpu: 0, mem: 32768, disk: 20480 }, queue: 'high-priority',
      timeout: 7200, retry: 1, interval: 120, strategy: 'RETRY', mutex: '', tags: ['etl-heavy'] },
    { id: 's5', name: '模型评分', type: 'TASK', op: 'OP-0005', opVer: 'v1', x: 880, y: 90,
      params: { inputPath: '${step.特征计算.output.outputFilePath}', modelPath: '/data/models/risk_v37.pkl', threshold: '0.62', gpuCount: 2 },
      res: { cpu: 8, gpu: 2, mem: 16384, disk: 8192 }, queue: 'gpu-queue',
      timeout: 5400, retry: 0, interval: null, strategy: 'TERMINATE', mutex: '', tags: ['gpu'] },
    { id: 's6', name: '结果入库', type: 'TASK', op: 'OP-0006', opVer: 'v1', x: 880, y: 330,
      params: { inputPath: '${step.特征计算.output.outputFilePath}', targetTable: 'dws.order_score_daily', writeMode: 'append' },
      res: { cpu: 4, gpu: 0, mem: 8192, disk: 4096 }, queue: 'batch-heavy',
      timeout: 3600, retry: 2, interval: 60, strategy: 'RETRY', mutex: '', tags: [] },
    { id: 's7', name: '报告推送', type: 'TASK', op: 'OP-0007', opVer: 'v1', x: 1160, y: 210,
      params: { scorePath: '${step.模型评分.output.scoreFilePath}', loadedRows: '${step.结果入库.output.loadedRows}', mailTo: 'ops-daily@example.com' },
      res: { cpu: 2, gpu: 0, mem: 4096, disk: 2048 }, queue: 'default',
      timeout: 1800, retry: 1, interval: 60, strategy: 'RETRY', mutex: '', tags: [] },
    { id: 'c1', name: 'MinIO·生产', type: 'CONFIG', mw: 'MinIO/S3', x: 880, y: -80,
      cfg: { endpoint: 'https://minio.prod.corp:9000', access_key: 'A9x2K7Qp****', secret_key: '••••••••（脱敏）', bucket: 'ecom-features' } },
    { id: 'c2', name: 'PostgreSQL·数仓', type: 'CONFIG', mw: 'PostgreSQL', x: 880, y: 470,
      cfg: { host: 'dw-pg-01', port: '5432', db: 'dws', user: 'etl_runner', password: '••••••••（脱敏）' } },
    { id: 'n1', name: '备注：清洗结果须通过质量校验（s2）与清洗（s3）双完成后才进入特征计算；互斥锁 orders_rw 防止与库存任务并发写同一分区', type: 'NOTE', x: 320, y: -60 }
  ];
  var wfEdges = [ {from:'s1',to:'s2'}, {from:'s1',to:'s3'}, {from:'s2',to:'s4'}, {from:'s3',to:'s4'}, {from:'s4',to:'s5'}, {from:'s4',to:'s6'}, {from:'s5',to:'s7'}, {from:'s6',to:'s7'}, {from:'c1',to:'s5'}, {from:'c2',to:'s6'} ];
  /* 配置引用与变量映射（连线语义：CONFIG → TASK） */
  wfSteps.filter(function (s) { return s.id === 's5'; })[0].cfgRefs = { c1: { map: { access_key: 'ak', secret_key: 'sk' } } };
  wfSteps.filter(function (s) { return s.id === 's6'; })[0].cfgRefs = { c2: { map: {} } };

  var workflows = [
    { id: 'WF-0001', name: '每日订单数据同步', project: 'PRJ-0001', status: 'PUBLISHED',
      currentVersion: 'v3', hasDraft: true, concurrency: 'FORBID', maxParallel: 1, affinity: false,
      lastRun: 'FAILED', lastRunAt: '2026-09-25 02:05:11', creator: 'pm-ecom', updated: '2026-09-24 18:22',
      steps: wfSteps, edges: wfEdges, dagHeight: 470,
      wfParams: [
        { key: 'bizDate', name: '业务日期', type: 'DATETIME', required: true, default: '${bizDate}', overridable: true, help: '默认取触发日' },
        { key: 'sourceSystem', name: '来源系统', type: 'SINGLE', required: false, default: 'order_ods', options: ['order_ods', 'order_his'], overridable: true, help: '' }
      ],
      notify: ['pm-ecom', 'biz-wang'] },
    { id: 'WF-0002', name: '用户画像宽表刷新', project: 'PRJ-0001', status: 'PUBLISHED',
      currentVersion: 'v7', hasDraft: false, concurrency: 'FORBID', maxParallel: 1, affinity: true,
      lastRun: 'SUCCESS', lastRunAt: '2026-09-25 03:41:02', creator: 'pm-ecom', updated: '2026-09-20 09:10', steps: [], edges: [] },
    { id: 'WF-0003', name: '风控指标日批', project: 'PRJ-0002', status: 'PUBLISHED',
      currentVersion: 'v2', hasDraft: false, concurrency: 'QUEUE', maxParallel: 2, affinity: false,
      lastRun: 'RUNNING', lastRunAt: '2026-09-25 04:10:44', creator: 'liu-min', updated: '2026-09-19 15:00', steps: [], edges: [] },
    { id: 'WF-0004', name: '商品主数据同步', project: 'PRJ-0001', status: 'DRAFT',
      currentVersion: '—', hasDraft: false, concurrency: 'FORBID', maxParallel: 1, affinity: false,
      lastRun: null, lastRunAt: null, creator: 'biz-wang', updated: '2026-09-22 10:31', steps: [], edges: [] },
    { id: 'WF-0005', name: '库存快照导出', project: 'PRJ-0002', status: 'PUBLISHED',
      currentVersion: 'v4', hasDraft: true, concurrency: 'FORBID', maxParallel: 1, affinity: false,
      lastRun: 'SUCCESS', lastRunAt: '2026-09-25 01:20:00', creator: 'liu-min', updated: '2026-09-21 14:02', steps: [], edges: [] },
    { id: 'WF-0006', name: '大促实时看板', project: 'PRJ-0002', status: 'DISABLED',
      currentVersion: 'v2', hasDraft: false, concurrency: 'ALLOW', maxParallel: 4, affinity: false,
      lastRun: 'STOPPED', lastRunAt: '2026-09-12 20:00:00', creator: 'liu-min', updated: '2026-09-12 19:00', steps: [], edges: [] }
  ];

  var triggers = [
    { id: 'TR-0001', wf: 'WF-0001', name: '每日02:00全量', type: 'CRON', cron: '0 2 * * *',
      lockedVersion: 'v3', enabled: true, timezone: 'Asia/Shanghai', catchUp: true, catchUpMax: 3,
      lastFire: '2026-09-25 02:00:03', lastStatus: 'FIRED', queue: 'high-priority',
      failNotify: { channels: ['EMAIL', 'WECOM'], receivers: ['pm-ecom'] },
      runParams: { bizDate: '${bizDate}', targetQueue: 'high-priority', priority: '8' } },
    { id: 'TR-0002', wf: 'WF-0001', name: '工作日每小时增量', type: 'CRON', cron: '0 9-18 * * 1-5',
      lockedVersion: null, enabled: false, timezone: 'Asia/Shanghai', catchUp: false, catchUpMax: 3,
      lastFire: '2026-09-24 18:00:02', lastStatus: 'FIRED', queue: 'default',
      failNotify: { channels: ['EMAIL'], receivers: ['pm-ecom'] },
      runParams: { sourceSystem: 'order_his' } },
    { id: 'TR-0003', wf: 'WF-0005', name: '库存快照·每日01:20', type: 'CRON', cron: '20 1 * * *',
      lockedVersion: 'v4', enabled: true, timezone: 'Asia/Shanghai', catchUp: true, catchUpMax: 3,
      lastFire: '2026-09-25 01:20:02', lastStatus: 'FIRED', queue: 'batch-heavy',
      failNotify: { channels: ['WEBHOOK'], receivers: ['liu-min'] },
      runParams: {} }
  ];

  /* ============ 域 5：任务（列表 18 行 + 主示例场景化） ============ 真源替换点：GET /api/tasks */
  var tasks = [
    { id: 'TASK-20260925-0042', wf: 'WF-0001', wfName: '每日订单数据同步', version: 'v3', project: 'PRJ-0001',
      trigger: 'CRON', submitter: '系统（TR-0001）', cluster: 'prod-compute-01', queue: 'high-priority',
      priority: 8, status: 'RUNNING', bizDate: '2026-09-25', submitAt: '2026-09-25 02:00:03',
      startAt: '2026-09-25 02:00:09', endAt: null, failReason: null, scene: 'running' },
    { id: 'TASK-20260925-0035', wf: 'WF-0001', wfName: '每日订单数据同步', version: 'v3', project: 'PRJ-0001',
      trigger: 'MANUAL', submitter: 'biz-wang', cluster: 'gpu-train-cluster', queue: 'gpu-queue',
      priority: 0, status: 'FAILED', bizDate: '2026-09-24', submitAt: '2026-09-24 22:10:00',
      startAt: '2026-09-24 22:10:04', endAt: '2026-09-25 01:12:39', failReason: '步骤「模型评分」失败：GPU OOM（exit 137），重试 2 次均失败', scene: 'failed' },
    { id: 'TASK-20260924-0028', wf: 'WF-0001', wfName: '每日订单数据同步', version: 'v2', project: 'PRJ-0001',
      trigger: 'CRON', submitter: '系统（TR-0001）', cluster: 'prod-compute-01', queue: 'high-priority',
      priority: 8, status: 'SUCCESS', bizDate: '2026-09-24', submitAt: '2026-09-24 02:00:02',
      startAt: '2026-09-24 02:00:08', endAt: '2026-09-24 04:18:33', scene: 'success' },
    { id: 'TASK-20260925-0051', wf: 'WF-0003', wfName: '风控指标日批', version: 'v2', project: 'PRJ-0002', trigger: 'CRON',
      submitter: '系统', cluster: 'gpu-train-cluster', queue: 'gpu-queue', priority: 10, status: 'PENDING', bizDate: '2026-09-25',
      submitAt: '2026-09-25 09:00:00', startAt: null, endAt: null, scene: 'scheduling' },
    { id: 'TASK-20260925-0110', wf: 'WF-0003', wfName: '风控指标日批', version: 'v2', project: 'PRJ-0002', trigger: 'API',
      submitter: 'API · 风控中台-生产', cluster: 'gpu-train-cluster', queue: 'gpu-queue', priority: 10, status: 'RUNNING', bizDate: '2026-09-25',
      submitAt: '2026-09-25 15:12:03', startAt: '2026-09-25 15:12:09', endAt: null, scene: 'running' },
    { id: 'TASK-20260925-0052', wf: 'WF-0005', wfName: '库存快照导出', version: 'v4', project: 'PRJ-0002', trigger: 'CRON',
      submitter: '系统（TR-0003）', cluster: 'prod-compute-02', queue: 'batch-heavy', priority: 5, status: 'SCHEDULING', bizDate: '2026-09-25',
      submitAt: '2026-09-25 01:20:02', startAt: null, endAt: null, scene: 'scheduling' },
    { id: 'TASK-20260925-0038', wf: 'WF-0002', wfName: '用户画像宽表刷新', version: 'v7', project: 'PRJ-0001', trigger: 'MANUAL',
      submitter: 'pm-ecom', cluster: 'prod-compute-01', queue: 'high-priority', priority: 20, status: 'STOPPING', bizDate: '2026-09-25',
      submitAt: '2026-09-25 14:22:00', startAt: '2026-09-25 14:22:05', endAt: null, stoppedBy: 'ops001', stopReason: '业务要求临时让路大促回填批次', scene: 'running' },
    { id: 'TASK-20260925-0022', wf: 'WF-0002', wfName: '用户画像宽表刷新', version: 'v7', project: 'PRJ-0001', trigger: 'CRON',
      submitter: '系统', cluster: 'prod-compute-01', queue: 'default', priority: 0, status: 'TIMEOUT', bizDate: '2026-09-25',
      submitAt: '2026-09-25 03:00:00', startAt: '2026-09-25 03:00:06', endAt: '2026-09-25 06:00:06', failReason: '任务级超时（3h），已终止运行中步骤', scene: 'timeout' },
    { id: 'TASK-20260924-0030', wf: 'WF-0003', wfName: '风控指标日批', version: 'v2', project: 'PRJ-0002', trigger: 'BACKFILL',
      submitter: 'liu-min（回填 BF-0003）', cluster: 'gpu-train-cluster', queue: 'gpu-queue', priority: 0, status: 'PARTIAL', bizDate: '2026-09-17',
      submitAt: '2026-09-22 10:00:00', startAt: '2026-09-22 10:00:05', endAt: '2026-09-22 11:40:00', scene: 'success' },
    { id: 'TASK-20260925-0019', wf: 'WF-0002', wfName: '用户画像宽表刷新', version: 'v7', project: 'PRJ-0001', trigger: 'MANUAL',
      submitter: 'biz-wang', cluster: 'prod-compute-01', queue: 'default', priority: 0, status: 'STOPPED', bizDate: '2026-09-25',
      submitAt: '2026-09-25 10:11:00', startAt: '2026-09-25 10:11:04', endAt: '2026-09-25 10:36:20', stoppedBy: 'biz-wang', stopReason: '参数填错，重新提交', scene: 'success' },
    { id: 'TASK-20260925-0044', wf: 'WF-0005', wfName: '库存快照导出', version: 'v4', project: 'PRJ-0002', trigger: 'BACKFILL',
      submitter: 'liu-min（回填 BF-0007）', cluster: 'prod-compute-02', queue: 'batch-heavy', priority: 0, status: 'RUNNING', bizDate: '2026-09-18',
      submitAt: '2026-09-25 14:30:00', startAt: '2026-09-25 14:30:06', endAt: null, scene: 'running' },
    { id: 'TASK-20260924-0061', wf: 'WF-0002', wfName: '用户画像宽表刷新', version: 'v7', project: 'PRJ-0001', trigger: 'CRON',
      submitter: '系统', cluster: 'prod-compute-01', queue: 'default', priority: 0, status: 'SUCCESS', bizDate: '2026-09-24',
      submitAt: '2026-09-24 03:00:01', startAt: '2026-09-24 03:00:07', endAt: '2026-09-24 05:12:44', scene: 'success' },
    { id: 'TASK-20260924-0058', wf: 'WF-0003', wfName: '风控指标日批', version: 'v2', project: 'PRJ-0002', trigger: 'CRON',
      submitter: '系统', cluster: 'gpu-train-cluster', queue: 'gpu-queue', priority: 10, status: 'FAILED', bizDate: '2026-09-24',
      submitAt: '2026-09-24 04:00:00', startAt: '2026-09-24 04:00:05', endAt: '2026-09-24 04:31:17', failReason: '步骤「指标计算」失败：依赖分区不存在（exit 2）', scene: 'failed' },
    { id: 'TASK-20260924-0049', wf: 'WF-0005', wfName: '库存快照导出', version: 'v4', project: 'PRJ-0002', trigger: 'MANUAL',
      submitter: 'sun-qian', cluster: 'prod-compute-02', queue: 'batch-heavy', priority: 0, status: 'SUCCESS', bizDate: '2026-09-24',
      submitAt: '2026-09-24 15:02:00', startAt: '2026-09-24 15:02:04', endAt: '2026-09-24 15:26:41', scene: 'success' },
    { id: 'TASK-20260923-0033', wf: 'WF-0001', wfName: '每日订单数据同步', version: 'v3', project: 'PRJ-0001', trigger: 'CRON',
      submitter: '系统（TR-0001）', cluster: 'prod-compute-01', queue: 'high-priority', priority: 8, status: 'SUCCESS', bizDate: '2026-09-23',
      submitAt: '2026-09-23 02:00:02', startAt: '2026-09-23 02:00:08', endAt: '2026-09-23 04:22:51', scene: 'success' },
    { id: 'TASK-20260923-0031', wf: 'WF-0003', wfName: '风控指标日批', version: 'v2', project: 'PRJ-0002', trigger: 'CRON',
      submitter: '系统', cluster: 'gpu-train-cluster', queue: 'gpu-queue', priority: 10, status: 'SUCCESS', bizDate: '2026-09-23',
      submitAt: '2026-09-23 04:00:00', startAt: '2026-09-23 04:00:06', endAt: '2026-09-23 05:05:12', scene: 'success' },
    { id: 'TASK-20260923-0027', wf: 'WF-0002', wfName: '用户画像宽表刷新', version: 'v7', project: 'PRJ-0001', trigger: 'CRON',
      submitter: '系统', cluster: 'prod-compute-01', queue: 'default', priority: 0, status: 'FAILED', bizDate: '2026-09-23',
      submitAt: '2026-09-23 03:00:00', startAt: '2026-09-23 03:00:05', endAt: '2026-09-23 03:47:29', failReason: '步骤「画像拼装」失败：HDFS 配额不足（exit 1）', scene: 'failed' },
    { id: 'TASK-20260922-0015', wf: 'WF-0005', wfName: '库存快照导出', version: 'v4', project: 'PRJ-0002', trigger: 'BACKFILL',
      submitter: 'liu-min（回填 BF-0006）', cluster: 'prod-compute-02', queue: 'batch-heavy', priority: 0, status: 'TIMEOUT', bizDate: '2026-09-14',
      submitAt: '2026-09-21 16:02:00', startAt: '2026-09-21 16:02:05', endAt: '2026-09-21 17:32:05', failReason: '步骤级等待资源超时（90 分钟），队列 batch-heavy 拥堵', scene: 'timeout' },
    { id: 'TASK-20260922-0009', wf: 'WF-0006', wfName: '大促实时看板', version: 'v2', project: 'PRJ-0002', trigger: 'MANUAL',
      submitter: 'liu-min', cluster: 'gpu-train-cluster', queue: 'gpu-queue', priority: 15, status: 'STOPPED', bizDate: '2026-09-12',
      submitAt: '2026-09-12 19:00:00', startAt: '2026-09-12 19:00:04', endAt: '2026-09-12 20:00:00', stoppedBy: 'liu-min', stopReason: '大促结束，任务下线', scene: 'success' }
  ];

  /* ============ 主示例任务的场景化数据（task-detail 使用） ============ */
  var showcase = {
    taskId: 'TASK-20260925-0042',
    stepDef: [
      { id:'s1', name:'数据抽取', op:'订单数据抽取@v1', deps:[] },
      { id:'s2', name:'数据校验', op:'数据质量校验@v2', deps:['s1'] },
      { id:'s3', name:'数据清洗', op:'Spark数据清洗@v3', deps:['s1'] },
      { id:'s4', name:'特征计算', op:'特征计算@v1', deps:['s2','s3'] },
      { id:'s5', name:'模型评分', op:'模型评分@v1', deps:['s4'] },
      { id:'s6', name:'结果入库', op:'结果入库@v1', deps:['s4'] },
      { id:'s7', name:'报告推送', op:'报告推送@v1', deps:['s5','s6'] }
    ],
    scenes: {
      running: { status:'RUNNING', nowOffset:5108, waitSec:6,
        steps:{ s1:'SUCCESS', s2:'SUCCESS', s3:'SUCCESS', s4:'SUCCESS', s5:'RUNNING', s6:'RUNNING', s7:'NOT_STARTED' } },
      scheduling: { status:'PENDING', nowOffset:0, waitSec:4520,
        steps:{ s1:'WAITING_DEPENDENCY', s2:'NOT_STARTED', s3:'NOT_STARTED', s4:'NOT_STARTED', s5:'NOT_STARTED', s6:'NOT_STARTED', s7:'NOT_STARTED' },
        diagOnly:true },
      success: { status:'SUCCESS', nowOffset:5778, waitSec:6,
        steps:{ s1:'SUCCESS', s2:'SUCCESS', s3:'SUCCESS', s4:'SUCCESS', s5:'SUCCESS', s6:'SUCCESS', s7:'SUCCESS' } },
      failed: { status:'FAILED', nowOffset:4666, waitSec:6,
        steps:{ s1:'SUCCESS', s2:'SUCCESS', s3:'SUCCESS', s4:'FAILED', s5:'STOPPED', s6:'STOPPED', s7:'NOT_STARTED' },
        failReason:'步骤「特征计算」失败：容器被 OOM Killer 终止（峰值内存 31.8GB / 上限 32GB），重试 2 次均失败',
        failCode:'RESOURCE_EXHAUSTED' },
      timeout: { status:'TIMEOUT', nowOffset:7788, waitSec:6,
        steps:{ s1:'SUCCESS', s2:'SUCCESS', s3:'SUCCESS', s4:'SUCCESS', s5:'TIMEOUT', s6:'SUCCESS', s7:'NOT_STARTED' },
        failReason:'步骤「模型评分」超过步骤超时 5400s，已强制终止进程' }
    },
    meta: {
      s1: { cluster:'prod-compute-01', queue:'high-priority', node:'prod-node-014', ip:'10.20.31.14', cred:'CR-0001（****a91f）',
        dur:412, wait:3, exit:0, res:{ cpu:[4,3.1], mem:[8,6.4], gpu:[0,0] },
        params:[ ['sourceDb','hive://dw-prod:10000/ods.order','平台变量'], ['bizDate','2026-09-25','工作流参数'], ['dataRoot','/data/ecom','项目参数'], ['targetQueue','high-priority','触发时覆盖'] ],
        cmd:'/usr/bin/beeline -u jdbc:hive2://dw-prod:10000 -n etl_runner -f /opt/ops/sql/ods_order_extract.sql --hivevar dt=2026-09-25 --hiveconf target=/data/ods/order/dt=2026-09-25',
        outs:[ { n:'outputFilePath', label:'抽取文件路径', v:'/data/ods/order/dt=2026-09-25/part-extract-0912.parquet' }, { n:'rowCount', label:'扫描行数', v:'1284766' } ] },
      s2: { cluster:'prod-compute-01', queue:'high-priority', node:'prod-node-022', ip:'10.20.31.22', cred:'CR-0001（****a91f）',
        dur:97, wait:2, exit:0, res:{ cpu:[2,1.4], mem:[4,2.8], gpu:[0,0] },
        params:[ ['inputPath','/data/ods/order/dt=2026-09-25/part-extract-0912.parquet','上游步骤输出'], ['rulesPath','/data/ecom/rules/order_rules_v12.json','项目参数'], ['threshold','0.998','工作流参数'] ],
        cmd:'/usr/bin/python3 /opt/ops/qc/validate.py --input /data/ods/order/dt=2026-09-25/part-extract-0912.parquet --rules /data/ecom/rules/order_rules_v12.json --threshold 0.998',
        outs:[ { n:'passRate', label:'校验通过率', v:'0.9997' }, { n:'invalidRows', label:'非法行数', v:'37' } ] },
      s3: { cluster:'prod-compute-01', queue:'high-priority', node:'prod-node-031', ip:'10.20.31.31', cred:'CR-0001（****a91f）',
        dur:1486, wait:11, exit:0, mutexWait:'等待互斥锁 orders_rw（持有者：TASK-20260924-0028 / 步骤 数据清洗），11s 后获得',
        res:{ cpu:[16,11.8], mem:[26,22.5], gpu:[0,0] },
        params:[ ['inputPath','/data/ods/order/dt=2026-09-25/part-extract-0912.parquet','上游步骤输出'], ['cleanedPath','/data/dwd/order_clean/dt=2026-09-25','步骤参数'], ['dedupKey','order_id','步骤参数'], ['executors','4','触发时覆盖'] ],
        cmd:'/usr/bin/spark-submit --master yarn --deploy-mode cluster --executor-memory 22g --executor-cores 4 --num-executors 4 --class com.ecom.etl.CleanJob /opt/ops/jars/spark-clean-3.1.0.jar --input /data/ods/order/dt=2026-09-25/part-extract-0912.parquet --output /data/dwd/order_clean/dt=2026-09-25 --dedup-key order_id',
        outs:[ { n:'cleanedFilePath', label:'清洗后文件路径', v:'/data/dwd/order_clean/dt=2026-09-25' } ] },
      s4: { cluster:'prod-compute-01', queue:'high-priority', node:'prod-node-007', ip:'10.20.31.7', cred:'CR-0001（****a91f）',
        dur:2244, wait:24, exit:0, res:{ cpu:[16,15.6], mem:[32,31.2], gpu:[0,0] },
        params:[ ['inputPath','/data/dwd/order_clean/dt=2026-09-25','上游步骤输出'], ['bizDate','2026-09-25','工作流参数'], ['featureDim','286','步骤参数'], ['taskId','TASK-20260925-0042','平台变量'] ],
        cmd:'/usr/bin/spark-submit --master yarn --deploy-mode cluster --executor-memory 30g --executor-cores 4 --num-executors 4 --class com.ecom.feature.FeatureJob /opt/ops/jars/spark-feature-2.7.3.jar --input /data/dwd/order_clean/dt=2026-09-25 --biz-date 2026-09-25 --dim 286 --output /data/dws/order_feature/dt=2026-09-25',
        outs:[ { n:'outputFilePath', label:'特征文件路径', v:'/data/dws/order_feature/dt=2026-09-25' }, { n:'featureDim', label:'特征维度', v:'286' } ] },
      s5: { cluster:'gpu-train-cluster', queue:'gpu-queue', node:'gpu-node-003', ip:'10.20.44.203', cred:'CR-0003（****77a0）',
        dur:1268, wait:8, exit:0, res:{ cpu:[8,5.2], mem:[16,12.4], gpu:[2,1.7] },
        params:[ ['inputPath','/data/dws/order_feature/dt=2026-09-25','上游步骤输出'], ['modelPath','/data/models/risk_v37.pkl','项目参数'], ['threshold','0.62','工作流参数'], ['gpuCount','2','触发时覆盖'], ['ak','A9x****（脱敏）','配置注入 · MinIO·生产（access_key→ak）'], ['sk','••••••••（脱敏）','配置注入 · MinIO·生产（secret_key→sk）'] ],
        cmd:'/opt/gpu/venv/bin/python /opt/ops/model/score.py --model /data/models/risk_v37.pkl --input /data/dws/order_feature/dt=2026-09-25 --threshold 0.62 --gpus 2 --output /data/dws/order_score/dt=2026-09-25',
        outs:[ { n:'scoreFilePath', label:'评分结果路径', v:'/data/dws/order_score/dt=2026-09-25' }, { n:'aucValue', label:'模型 AUC', v:'0.8471' } ] },
      s6: { cluster:'prod-compute-02', queue:'batch-heavy', node:'prod2-node-011', ip:'10.20.32.11', cred:'CR-0001（****a91f）',
        dur:533, wait:5, exit:0, res:{ cpu:[4,2.9], mem:[8,5.1], gpu:[0,0] },
        params:[ ['inputPath','/data/dws/order_score/dt=2026-09-25','上游步骤输出'], ['targetTable','dws.order_score_daily','项目参数'], ['writeMode','append','步骤参数'] ],
        cmd:'/usr/bin/sqoop export --connect jdbc:postgresql://dw-pg-01:5432/dws --table order_score_daily --export-dir /data/dws/order_score/dt=2026-09-25 --input-fields-terminated-by \\t --batch',
        outs:[ { n:'loadedRows', label:'入库行数', v:'1284729' } ] },
      s7: { cluster:'prod-compute-01', queue:'default', node:'prod-node-014', ip:'10.20.31.14', cred:'CR-0001（****a91f）',
        dur:318, wait:4, exit:0, res:{ cpu:[2,1.1], mem:[4,2.2], gpu:[0,0] },
        params:[ ['scorePath','/data/dws/order_score/dt=2026-09-25','上游步骤输出'], ['loadedRows','1284729','上游步骤输出'], ['mailTo','ops-daily@example.com','工作流参数'] ],
        cmd:'/opt/report/bin/gen_report --template /data/templates/order_report_v3.xlsx --score /data/dws/order_score/dt=2026-09-25 --rows 1284729 --out /data/report/daily/2026-09-25/order_report.xlsx',
        outs:[ { n:'reportFilePath', label:'报表文件路径', v:'/data/report/daily/2026-09-25/order_report.xlsx' } ] }
    },
    attempts: {
      s3: [ { n:1, start:'02:07:29', end:'02:22:19', dur:'14m 50s', exit:137, result:'FAILED', node:'prod-node-031', note:'Executor 容器被 YARN 回收：超出队列 vcore 上限，已自动重试' },
            { n:2, start:'02:22:49', end:'02:47:35', dur:'24m 46s', exit:0, result:'SUCCESS', node:'prod-node-034', note:'调整 executor 数量 4 → 3 后正常完成' } ],
      s4: [ { n:1, start:'02:32:39', end:'02:52:19', dur:'19m 40s', exit:137, result:'FAILED', node:'prod-node-007', note:'退出码 137：容器被 OOM Killer 终止，峰值内存 31.8GB / 上限 32GB' },
            { n:2, start:'02:52:49', end:'03:18:09', dur:'25m 20s', exit:0, result:'SUCCESS', node:'prod-node-011', note:'降低 executor 并发后重试成功' } ]
    },
    /* 失败场景（重试耗尽）专用：s4 两次尝试均 OOM，与 success 场景的恢复历史区分 */
    attemptsFailed: {
      s4: [ { n:1, start:'02:32:39', end:'02:52:19', dur:'19m 40s', exit:137, result:'FAILED', node:'prod-node-007', note:'退出码 137：容器被 OOM Killer 终止，峰值内存 31.8GB / 上限 32GB' },
            { n:2, start:'02:52:49', end:'03:12:29', dur:'19m 40s', exit:137, result:'FAILED', node:'prod-node-011', note:'重试再次 OOM（峰值 31.9GB / 上限 32GB），重试次数耗尽，下游步骤全部终止' } ]
    },
    diag: {
      queuePos:'第 7 位（共 12 个等待任务）', queueConc:'6 / 6 已占满', waited:'1h 15m 20s',
      block:'等待满足 [CPU ≥ 8 核 且 内存 ≥ 32GB 且 标签含 etl-heavy] 的 Linux 执行节点',
      nearMiss:[
        { node:'prod-node-014', detail:'空闲 1 台（可用 4 核 / 16GB，不满足 8 核 / 32GB）' },
        { node:'prod-node-007', detail:'标签匹配但 GPU 内存余量不足，全部分配中' },
        { node:'gpu-node-003', detail:'属于 gpu-train-cluster，与目标集群不符' }
      ],
      eta:'约 12 分钟后（基于 high-priority 队列近 7 天平均排队时长估算）',
      suggestions:['将资源申请从 8 核/32GB 降至 4 核/16GB', '移除 etl-heavy 标签约束', '联系运维对 high-priority 队列扩容并发'],
      chain:[
        { t:'任务已提交', d:'2026-09-25 02:00:03 · 定时触发器 TR-0001', s:'ok' },
        { t:'并发互斥检查通过', d:'工作流并发策略 FORBID，当前无其他运行实例', s:'ok' },
        { t:'变量解析与快照生成', d:'已解析 9 个平台变量 · 16 个参数（含 1 个敏感参数，已脱敏）', s:'ok' },
        { t:'进入队列 high-priority', d:'队列并发上限 6，当前 6 个任务运行中（已满）', s:'ok' },
        { t:'等待可用的执行节点', d:'已等待 1h 15m 20s · 排队位置 7 / 12', s:'now' },
        { t:'下发至执行节点', d:'未开始', s:'' },
        { t:'步骤开始执行', d:'未开始', s:'' }
      ]
    },
    varDefs: [
      { n:'taskId', v:'TASK-20260925-0042', l:0, t:'本次任务唯一编号，随每次触发重新生成' },
      { n:'runId', v:'run-7f2a91c4', l:0, t:'本次运行实例 ID，重试与重跑均生成新 runId' },
      { n:'workflowName', v:'每日订单数据同步', l:0, t:'工作流名称，发布后不可修改' },
      { n:'workflowVersion', v:'v3', l:0, t:'本次执行使用的工作流版本快照' },
      { n:'projectName', v:'电商数据平台', l:0, t:'任务所属项目空间' },
      { n:'triggerTime', v:'2026-09-25 02:00:00', l:0, t:'触发器实际触发时间（非任务开始执行时间）' },
      { n:'triggerUser', v:'系统（定时调度器）', l:0, t:'手动触发为用户名，定时触发为调度器' },
      { n:'queueName', v:'high-priority', l:0, t:'目标队列，由触发器或触发时参数指定' },
      { n:'project.dataRoot', v:'/data/ecom', l:1, t:'项目级数据根目录，全项目工作流共用' },
      { n:'project.dwSchema', v:'ecommerce_dw', l:1, t:'项目绑定的数仓 Schema' },
      { n:'project.dbPassword', v:'••••••••（已脱敏）', l:1, t:'敏感参数，始终脱敏，永不回显', masked:true },
      { n:'workflow.bizDate', v:'2026-09-25', l:2, t:'业务日期，未覆盖时默认取触发当日' },
      { n:'workflow.sourceSystem', v:'order_ods', l:2, t:'数据来源系统标识' },
      { n:'workflow.timeoutSec', v:'3600', l:2, t:'步骤级默认超时时间（秒）' },
      { n:'trigger.targetQueue', v:'high-priority', l:3, t:'触发时覆盖了工作流默认队列' },
      { n:'trigger.priority', v:'8（高）', l:3, t:'触发时覆盖了工作流默认优先级' },
      { n:'steps.数据抽取.outputFilePath', v:'/data/ods/order/dt=2026-09-25/part-extract-0912.parquet', l:4, by:'数据抽取', t:'由「数据抽取」输出声明 outputFilePath 提供' },
      { n:'steps.数据抽取.rowCount', v:'1284766', l:4, by:'数据抽取', t:'由「数据抽取」输出声明 rowCount 提供' },
      { n:'steps.数据校验.passRate', v:'0.9997', l:4, by:'数据校验', t:'由「数据校验」输出声明 passRate 提供' },
      { n:'steps.数据清洗.cleanedFilePath', v:'/data/dwd/order_clean/dt=2026-09-25', l:4, by:'数据清洗', t:'由「数据清洗」输出声明 cleanedFilePath 提供' },
      { n:'steps.特征计算.outputFilePath', v:'/data/dws/order_feature/dt=2026-09-25', l:4, by:'特征计算', t:'由「特征计算」输出声明提供' },
      { n:'steps.模型评分.scoreFilePath', v:'/data/dws/order_score/dt=2026-09-25', l:4, by:'模型评分', t:'由「模型评分」输出声明提供' }
    ],
    logs: {
      s1: [ ['sys','步骤启动 · 算子 订单数据抽取@v1 · 执行机器 prod-node-014 (10.20.31.14)'],
        ['sys','已注入变量 4 个 · 其中上游步骤输出 0 个 · 敏感参数 0 个'],
        ['out','INFO  ExtractJob - 连接上游数据源 hive://dw-prod:10000/ods.order'],
        ['out','INFO  ExtractJob - 连接成功，Hive 会话 ID hive-9f2c41a8'],
        ['out','INFO  ExtractJob - 目标分区 dt=2026-09-25，预计扫描 1284 万行'],
        ['out','INFO  ExtractJob - 启动 12 个 extractor 并发读取'],
        ['out','INFO  ExtractJob - 进度 25% · 已读取 321.2 万行'],
        ['out','INFO  ExtractJob - 进度 50% · 已读取 642.4 万行'],
        ['warn','WARN  ExtractJob - 分区 dt=2026-09-24 存在 3 个小文件，读取耗时 +4.2s'],
        ['out','INFO  ExtractJob - 进度 100% · 已读取 1284.8 万行'],
        ['out','INFO  ExtractJob - 写入 parquet 完成，文件数 8，压缩 snappy'],
        ['data','OUTPUT_PATH=/data/ods/order/dt=2026-09-25/part-extract-0912.parquet'],
        ['data','ROWS=1284766'],
        ['sys','输出声明已注册：outputFilePath, rowCount（2 项）'],
        ['sys','退出码 0 · 耗时 6m 52s · 状态 SUCCESS'] ],
      s2: [ ['sys','步骤启动 · 算子 数据质量校验@v2 · 执行机器 prod-node-022 (10.20.31.22)'],
        ['sys','已注入变量 3 个 · 其中上游步骤输出 1 个'],
        ['out','INFO  ValidateJob - 加载校验规则 order_rules_v12.json，共 42 条规则'],
        ['out','INFO  ValidateJob - 输入 /data/ods/order/dt=2026-09-25/part-extract-0912.parquet'],
        ['out','INFO  ValidateJob - 必填字段检查通过：order_id, user_id, amount, pay_time'],
        ['warn','WARN  ValidateJob - 字段 order_amount 存在 37 条非法空值，已标记为待清洗'],
        ['out','INFO  ValidateJob - 主键唯一性校验通过，重复率 0.0000%'],
        ['out','INFO  ValidateJob - 时间范围校验通过：2026-09-01 ~ 2026-09-24'],
        ['data','PASS_RATE=0.9997'],
        ['data','INVALID=37'],
        ['sys','输出声明已注册：passRate, invalidRows（2 项）'],
        ['sys','退出码 0 · 耗时 1m 37s · 状态 SUCCESS'] ],
      s3: [ ['sys','步骤启动 · 算子 Spark数据清洗@v3 · 第 2 次尝试（上次退出码 137）'],
        ['sys','互斥锁 orders_rw 获取成功（等待 11s，上一持有者已释放）'],
        ['out','INFO  CleanJob - Spark 应用 application_1758331200441_0917 已提交'],
        ['out','INFO  CleanJob - 执行机器 prod-node-034 · executor 3 × 4 core · 22GB'],
        ['out','INFO  CleanJob - 读取 /data/ods/order/dt=2026-09-25/part-extract-0912.parquet'],
        ['out','INFO  CleanJob - 空值填充策略：order_amount → 0.0，remark → ""'],
        ['out','INFO  CleanJob - 按 order_id 去重，剔除重复记录 1,204 条'],
        ['out','INFO  CleanJob - 异常金额（<=0 或 >1e7）修正 86 条'],
        ['out','INFO  CleanJob - 清洗后行数 1,284,729'],
        ['data','CLEAN_PATH=/data/dwd/order_clean/dt=2026-09-25'],
        ['sys','输出声明已注册：cleanedFilePath（1 项）'],
        ['sys','退出码 0 · 耗时 24m 46s · 状态 SUCCESS（含第 1 次失败重试）'] ],
      s4: [ ['sys','步骤启动 · 算子 特征计算@v1 · 执行机器 prod-node-007 (10.20.31.7)'],
        ['sys','已注入变量 4 个 · 其中上游步骤输出 1 个'],
        ['out','INFO  FeatureJob - Spark 应用 application_1758331200441_0923 已提交'],
        ['out','INFO  FeatureJob - 执行机器 prod-node-007 · executor 4 × 4 core · 30GB'],
        ['out','INFO  FeatureJob - 读取 /data/dwd/order_clean/dt=2026-09-25，分区数 8'],
        ['out','INFO  FeatureJob - 特征工程阶段 1/3：用户维度聚合（下单频次 / 客单价 / 最近下单间隔）'],
        ['out','INFO  FeatureJob - 特征工程阶段 2/3：商品类目向量化，维度 128'],
        ['out','INFO  FeatureJob - 特征工程阶段 3/3：时间滑窗特征，窗口 7d / 30d / 90d'],
        ['out','INFO  FeatureJob - 特征矩阵构建完成：1,284,729 行 × 286 列'],
        ['out','INFO  FeatureJob - 稀疏化处理完成，稀疏度 62.4%'],
        ['out','INFO  FeatureJob - 写入 /data/dws/order_feature/dt=2026-09-25 完成'],
        ['data','FEATURE_PATH=/data/dws/order_feature/dt=2026-09-25'],
        ['sys','输出声明已注册：outputFilePath, featureDim（2 项）'],
        ['sys','退出码 0 · 耗时 37m 24s · 状态 SUCCESS'] ],
      s5: [ ['sys','步骤启动 · 算子 模型评分@v1 · 执行机器 gpu-node-003 (10.20.44.203)'],
        ['sys','已注入变量 4 个 · 其中上游步骤输出 1 个'],
        ['out','INFO  ScoreJob - 加载模型 /data/models/risk_v37.pkl（LightGBM，286 特征）'],
        ['out','INFO  ScoreJob - 已绑定 GPU 2 张 · 驱动 550.54.15 · CUDA 12.4'],
        ['out','INFO  ScoreJob - 输入 /data/dws/order_feature/dt=2026-09-25 · 1,284,729 行'],
        ['out','INFO  ScoreJob - 分批推理启动，batch_size=16384，总批次 79'],
        ['out','INFO  Batch 10/79 完成 · 已评分 163,840 行 · GPU 利用率 78%'],
        ['out','INFO  Batch 20/79 完成 · 已评分 327,680 行 · GPU 利用率 81%'],
        ['out','INFO  Batch 30/79 完成 · 已评分 491,520 行 · GPU 利用率 83%'],
        ['out','INFO  Batch 40/79 完成 · 已评分 655,360 行 · GPU 利用率 80%'],
        ['out','INFO  Batch 47/79 完成 · 已评分 770,048 行 · 平均耗时 6.9s/批'] ],
      s6: [ ['sys','步骤启动 · 算子 结果入库@v1 · 执行机器 prod2-node-011 (10.20.32.11)'],
        ['sys','已注入变量 3 个 · 其中上游步骤输出 1 个'],
        ['out','INFO  LoadJob - 连接目标库 jdbc:postgresql://dw-pg-01:5432/dws · 连接池 8'],
        ['out','INFO  LoadJob - 目标表 dws.order_score_daily 写入模式 append'],
        ['out','INFO  LoadJob - 建表检查通过，字段类型自动映射 12 列'],
        ['out','INFO  LoadJob - 开始批量写入，批次 1/13'],
        ['out','INFO  LoadJob - 已写入 100,000 行'],
        ['out','INFO  LoadJob - 已写入 500,000 行'],
        ['out','INFO  LoadJob - 已写入 1,000,000 行'],
        ['data','LOADED=1284729'],
        ['sys','输出声明已注册：loadedRows（1 项）'],
        ['sys','退出码 0 · 耗时 8m 53s · 状态 SUCCESS'] ],
      s7: [ ['sys','步骤启动 · 算子 报告推送@v1 · 执行机器 prod-node-014 (10.20.31.14)'],
        ['sys','已注入变量 4 个 · 其中上游步骤输出 2 个'],
        ['out','INFO  ReportGen - 加载模板 order_report_v3.xlsx'],
        ['out','INFO  ReportGen - 汇总当日评分分布：低风险 89.4% / 中风险 8.9% / 高风险 1.7%'],
        ['out','INFO  ReportGen - 生成 3 个透视表与 2 张折线图'],
        ['out','INFO  ReportGen - 导出 /data/report/daily/2026-09-25/order_report.xlsx'],
        ['out','INFO  ReportGen - 已推送至邮件组 ops-daily@example.com'],
        ['sys','输出声明已注册：reportFilePath（1 项）'],
        ['sys','退出码 0 · 耗时 5m 18s · 状态 SUCCESS'] ]
    },
    livePool: {
      s5: [ ['out','INFO  Batch {n}/79 完成 · 已评分 {rows} 行 · 平均耗时 6.9s/批'],
        ['out','INFO  ScoreJob - 心跳上报正常 · GPU 显存占用 11.8GB / 24GB'],
        ['out','INFO  ScoreJob - 已写入中间结果分区 part-00{part}'],
        ['warn','WARN  ScoreJob - 单批次耗时超过 8.0s 阈值（当前 8.3s），已记录但不影响执行'] ],
      s6: [ ['out','INFO  LoadJob - 心跳上报正常 · 连接池可用 6/8'],
        ['out','INFO  LoadJob - 已写入 {rows} 行'],
        ['out','INFO  LoadJob - 当前写入速率 2,410 行/秒'] ]
    }
  };

  /* ============ 域 6：回填 ============ */
  var backfillBatches = [
    { id: 'BF-0003', wf: 'WF-0003', wfName: '风控指标日批', range: '2026-09-01 ~ 2026-09-07', conc: 1, eff: 1,
      status: 'DONE', total: 7, success: 6, failed: 1, waiting: 0, skipped: ['2026-09-05（冲突：已有成功任务）'], submitter: 'liu-min', submittedAt: '2026-09-22 09:58' },
    { id: 'BF-0007', wf: 'WF-0001', wfName: '每日订单数据同步', range: '2026-09-15 ~ 2026-09-21', conc: 3, eff: 1,
      status: 'RUNNING', total: 7, success: 5, failed: 0, waiting: 2, skipped: [], submitter: 'pm-ecom', submittedAt: '2026-09-25 14:30' },
    { id: 'BF-0009', wf: 'WF-0005', wfName: '库存快照导出', range: '2026-09-14 ~ 2026-09-20', conc: 2, eff: 1,
      status: 'RUNNING', total: 7, success: 3, failed: 1, waiting: 3, skipped: ['2026-09-16（冲突）'], submitter: 'liu-min', submittedAt: '2026-09-24 16:02' },
    { id: 'BF-0006', wf: 'WF-0002', wfName: '用户画像宽表刷新', range: '2026-09-10 ~ 2026-09-12', conc: 2, eff: 1,
      status: 'CANCELLED', total: 3, success: 1, failed: 0, waiting: 0, skipped: [], submitter: 'pm-ecom', submittedAt: '2026-09-21 16:02' },
    { id: 'BF-0002', wf: 'WF-0001', wfName: '每日订单数据同步', range: '2026-08-25 ~ 2026-08-31', conc: 1, eff: 1,
      status: 'PAUSED', total: 7, success: 4, failed: 0, waiting: 3, skipped: [], submitter: 'pm-ecom', submittedAt: '2026-09-19 11:20' }
  ];

  /* ============ 域 7：告警 ============ */
  var alertRules = [
    { id: 'AR-0001', name: '任务失败', event: 'TASK_FAILED', level: '严重', enabled: true, suppress: 30,
      channels: ['EMAIL', 'WECOM'], receivers: ['任务提交人', '项目管理员', '工作流通知人'], scope: '全局' },
    { id: 'AR-0002', name: '任务超时', event: 'TASK_TIMEOUT', level: '严重', enabled: true, suppress: 30,
      channels: ['EMAIL', 'WEBHOOK'], receivers: ['任务提交人', '项目管理员'], scope: '全局' },
    { id: 'AR-0003', name: '步骤重试耗尽', event: 'STEP_RETRY_EXHAUSTED', level: '严重', enabled: true, suppress: 60,
      channels: ['EMAIL'], receivers: ['项目管理员', '算子维护者'], scope: '全局' },
    { id: 'AR-0004', name: '执行节点离线', event: 'NODE_OFFLINE', level: '警告', enabled: true, suppress: 30,
      channels: ['WECOM'], receivers: ['运维人员'], scope: '全局' },
    { id: 'AR-0005', name: '工作流并发触达上限', event: 'CONCURRENCY_LIMIT', level: '提示', enabled: true, suppress: 60,
      channels: ['EMAIL'], receivers: ['任务提交人'], scope: '全局' },
    { id: 'AR-0006', name: '互斥锁持有超 30 分钟', event: 'MUTEX_LONG_HOLD', level: '警告', enabled: false, suppress: 60,
      channels: ['WECOM'], receivers: ['运维人员'], scope: '全局' }
  ];

  var alertChannels = [
    { type: 'EMAIL', name: '邮件网关', endpoint: 'smtp://mail-gateway.corp:25 → flowops-alert@corp.com', enabled: true, verified: true, lastTest: '2026-09-25 09:00 · 420ms · 成功' },
    { type: 'WEBHOOK', name: '通用 Webhook', endpoint: 'https://open.feishu.cn/open-apis/bot/v2/hook/****（已脱敏）', enabled: true, verified: true, lastTest: '2026-09-24 20:00 · 182ms · 成功' },
    { type: 'WECOM', name: '企业微信机器人', endpoint: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=****（已脱敏）', enabled: true, verified: false, lastTest: '未测试' }
  ];

  var alertRecords = [
    { id: 'AL-2401', time: '2026-09-25 01:12:41', event: '任务失败', level: '严重', target: 'TASK-20260925-0035',
      channels: ['EMAIL', 'WECOM'], result: 'SENT', latency: 'EMAIL 380ms · WECOM 122ms' },
    { id: 'AL-2400', time: '2026-09-25 00:58:03', event: '任务失败', level: '严重', target: 'TASK-20260925-0035',
      channels: ['EMAIL'], result: 'SUPPRESSED', latency: '抑制窗口 30 分钟内不重复发送' },
    { id: 'AL-2399', time: '2026-09-25 15:06:30', event: '执行节点离线', level: '警告', target: 'prod-node-019',
      channels: ['WECOM'], result: 'SENT', latency: 'WECOM 108ms' },
    { id: 'AL-2398', time: '2026-09-25 06:00:08', event: '任务超时', level: '严重', target: 'TASK-20260925-0022',
      channels: ['EMAIL', 'WEBHOOK'], result: 'SENT', latency: 'EMAIL 402ms · WEBHOOK 205ms' },
    { id: 'AL-2397', time: '2026-09-25 09:00:01', event: '工作流并发触达上限', level: '提示', target: 'WF-0001（触发被跳过）',
      channels: ['EMAIL'], result: 'SENT', latency: 'EMAIL 365ms' },
    { id: 'AL-2396', time: '2026-09-24 23:41:12', event: '步骤重试耗尽', level: '严重', target: 'TASK-20260924-0058 / 指标计算',
      channels: ['EMAIL'], result: 'SENT', latency: 'EMAIL 391ms' },
    { id: 'AL-2395', time: '2026-09-24 22:40:02', event: '任务失败', level: '严重', target: 'TASK-20260924-0058',
      channels: ['EMAIL', 'WECOM'], result: 'PARTIAL', latency: 'EMAIL 388ms · WECOM 超时（5s）' },
    { id: 'AL-2394', time: '2026-09-24 18:02:44', event: '任务失败', level: '严重', target: 'TASK-20260923-0027',
      channels: ['EMAIL', 'WECOM'], result: 'SENT', latency: 'EMAIL 401ms · WECOM 130ms' },
    { id: 'AL-2393', time: '2026-09-24 14:52:00', event: '执行节点离线', level: '警告', target: 'prod-node-019',
      channels: ['WECOM'], result: 'SUPPRESSED', latency: '同节点 30 分钟窗口内重复离线' },
    { id: 'AL-2392', time: '2026-09-24 02:41:00', event: '任务失败', level: '严重', target: 'TASK-20260924-0035（首次 OOM）',
      channels: ['EMAIL'], result: 'SENT', latency: 'EMAIL 377ms' }
  ];

  /* ============ 域 8：审计（55 必审动作抽样覆盖全 12 域） ============ */
  var auditLogs = [
    { id: 'AU-8852', time: '2026-09-25 15:28:11', operator: 'ops001', name: '运维-张三', action: 'ENQUEUE_FRONT', target: 'TASK / TASK-20260925-0051', result: 'SUCCESS', ip: '10.8.2.17', trace: '9f2c41ab7d0e4c5581a2b3c4d5e6f708', reason: null, diff: null },
    { id: 'AU-8851', time: '2026-09-25 15:06:30', operator: 'system', name: '调度器', action: 'FORCE_RELEASE_MUTEX', target: 'MUTEX / orders_rw（prod-node-019 失联步骤）', result: 'SUCCESS', ip: '—', trace: 'aa11bb22cc33dd44ee55ff6600112233', reason: '节点失联超恢复窗口，强制释放互斥锁', diff: null },
    { id: 'AU-8850', time: '2026-09-25 14:40:02', operator: 'ops001', name: '运维-张三', action: 'VIEW_LOG_RAW', target: 'TASK / TASK-20260925-0035 / 步骤 模型评分', result: 'SUCCESS', ip: '10.8.2.17', trace: 'b81d22c90a1140328765fedcba987654', reason: '排障：GPU OOM，需查看评分日志原文', diff: null, cross: true },
    { id: 'AU-8849', time: '2026-09-25 14:22:31', operator: 'ops001', name: '运维-张三', action: 'STOP_TASK', target: 'TASK / TASK-20260925-0038', result: 'SUCCESS', ip: '10.8.2.17', trace: '77e0a1c2558841ee9d33bb22ff8811aa', reason: '业务要求临时让路大促回填批次', diff: { status: ['RUNNING', 'STOPPING'] } },
    { id: 'AU-8848', time: '2026-09-25 11:02:45', operator: 'dev-lin', name: '开发-林一', action: 'UPLOAD_VERSION', target: 'OPERATOR_VERSION / OP-0003 v3', result: 'SUCCESS', ip: '10.8.3.44', trace: 'c02f8811442299aa55ee11bb33cc77dd', reason: null, diff: { file: ['spark-clean-3.0.2.jar', 'spark-clean-3.1.0.jar'] } },
    { id: 'AU-8847', time: '2026-09-25 10:31:20', operator: 'ops001', name: '运维-张三', action: 'CREATE_QUEUE', target: 'QUEUE / Q-0004 batch-heavy', result: 'SUCCESS', ip: '10.8.2.17', trace: 'd11a22bb33cc44dd55ee66ff77008899', reason: '大促批量回填需要独立队列', diff: { 'max_concurrent_tasks': ['（新建）', '4'], 'allow_jump_queue': ['（新建）', 'true'] } },
    { id: 'AU-8846', time: '2026-09-25 10:12:03', operator: 'ops001', name: '运维-张三', action: 'CREATE_NODE', target: 'EXECUTOR_NODE / prod2-node-011', result: 'SUCCESS', ip: '10.8.2.17', trace: 'e22f33aa44bb55cc66dd77ee88009900', reason: null, diff: { ip: ['（新建）', '10.20.32.11'], tags: ['（新建）', 'etl'] } },
    { id: 'AU-8845', time: '2026-09-25 09:58:41', operator: 'pm-ecom', name: '项目管理-陈晓明', action: 'SUBMIT_BACKFILL', target: 'BACKFILL / BF-0007', result: 'SUCCESS', ip: '10.8.5.21', trace: 'f33a44bb55cc66dd77ee88ff9900aa11', reason: '大促前补齐近 7 天特征数据', diff: { range: ['（新建）', '2026-09-15 ~ 2026-09-21'], eff_conc: ['（新建）', '1'] } },
    { id: 'AU-8844', time: '2026-09-24 18:22:09', operator: 'pm-ecom', name: '项目管理-陈晓明', action: 'PUBLISH_WORKFLOW', target: 'WORKFLOW / WF-0001 v3', result: 'SUCCESS', ip: '10.8.5.21', trace: 'aa39be07cc1144dd55ee66ff0011aa22', reason: null, diff: { '步骤数': ['4', '7'], '数据清洗.互斥锁': ['（空）', 'orders_rw'] } },
    { id: 'AU-8843', time: '2026-09-24 17:58:40', operator: 'pm-ecom', name: '项目管理-陈晓明', action: 'SAVE_DRAFT', target: 'WORKFLOW / WF-0001（草稿）', result: 'SUCCESS', ip: '10.8.5.21', trace: '1f80cc3133ee55ff66aa77bb2244cc33', reason: null, diff: { '特征计算.CPU': ['4', '16'] } },
    { id: 'AU-8842', time: '2026-09-24 16:41:12', operator: 'pm-ecom', name: '项目管理-陈晓明', action: 'UPDATE_TRIGGER', target: 'TRIGGER / TR-0001', result: 'SUCCESS', ip: '10.8.5.21', trace: '5d11f8aa44aa55bb66cc77dd3388ee44', reason: null, diff: { cron: ['0 3 * * *', '0 2 * * *'] } },
    { id: 'AU-8841', time: '2026-09-24 15:32:07', operator: 'ops001', name: '运维-张三', action: 'TEST_NODE', target: 'EXECUTOR_NODE / gpu-node-008', result: 'SUCCESS', ip: '10.8.2.17', trace: '66cc77dd88ee99ff0011aabb2233cc44', reason: null, diff: null },
    { id: 'AU-8840', time: '2026-09-24 15:06:30', operator: 'system', name: '调度器', action: 'NODE_OFFLINE_MARK', target: 'EXECUTOR_NODE / prod-node-019', result: 'SUCCESS', ip: '—', trace: '77dd88ee99ff00112233aabb4455dd55', reason: '连续 3 次心跳缺失（45s），判定离线', diff: { online_status: ['ONLINE', 'OFFLINE'] } },
    { id: 'AU-8839', time: '2026-09-24 11:20:15', operator: 'ops001', name: '运维-张三', action: 'MAINTENANCE_CLUSTER', target: 'CLUSTER / dev-cluster', result: 'SUCCESS', ip: '10.8.2.17', trace: '88ee99ff001122334455bbcc6677ee66', reason: 'dev 环境周末维护', diff: { status: ['NORMAL', 'MAINTENANCE'] } },
    { id: 'AU-8838', time: '2026-09-24 10:02:51', operator: 'pm-ecom', name: '项目管理-陈晓明', action: 'ADD_MEMBER', target: 'PROJECT / PRJ-0001 成员 王芳', result: 'SUCCESS', ip: '10.8.5.21', trace: '99ff0011223344556677ccdd8899ff77', reason: null, diff: { member: ['（新增）', '王芳（BUSINESS_USER）'] } },
    { id: 'AU-8837', time: '2026-09-24 09:44:33', operator: 'dev-zhao', name: '开发-赵磊', action: 'DRYRUN_OPERATOR', target: 'OPERATOR_VERSION / OP-0005 v1 @ gpu-node-008', result: 'SUCCESS', ip: '10.8.3.51', trace: 'aa001122334455667788ddeeff001122', reason: '验证 GPU 评分脚本参数', diff: null },
    { id: 'AU-8836', time: '2026-09-24 09:12:08', operator: 'ops001', name: '运维-张三', action: 'UPDATE_ALERT_RULE', target: 'ALERT_RULE / AR-0004', result: 'SUCCESS', ip: '10.8.2.17', trace: 'bb112233445566778899eeff00112334', reason: null, diff: { suppress_window_minutes: ['15', '30'] } },
    { id: 'AU-8835', time: '2026-09-23 20:40:19', operator: 'liu-min', name: '项目管理-刘敏', action: 'PAUSE_BACKFILL', target: 'BACKFILL / BF-0009', result: 'SUCCESS', ip: '10.8.4.8', trace: 'cc2233445566778899aabbccddee4455', reason: '白天集群压力大，夜间继续', diff: { status: ['RUNNING', 'PAUSED'] } },
    { id: 'AU-8834', time: '2026-09-23 18:41:55', operator: 'pm-ecom', name: '项目管理-陈晓明', action: 'DISABLE_WORKFLOW', target: 'WORKFLOW / WF-0006 大促实时看板', result: 'SUCCESS', ip: '10.8.5.21', trace: 'dd33445566778899aabbccddeeff5566', reason: '大促结束下线', diff: { status: ['PUBLISHED', 'DISABLED'] } },
    { id: 'AU-8833', time: '2026-09-23 16:02:31', operator: 'biz-wang', name: '业务-王芳', action: 'SUBMIT_TASK', target: 'TASK / TASK-20260924-0019（WF-0002）', result: 'FAIL', ip: '10.8.6.30', trace: 'ee445566778899aabbccddeeff001166', reason: null, diff: null, failReason: '参数校验失败：bizDate 格式错误（40001）' },
    { id: 'AU-8832', time: '2026-09-23 11:00:22', operator: 'ops001', name: '运维-张三', action: 'ROTATE_CREDENTIAL', target: 'CREDENTIAL / CR-0001', result: 'SUCCESS', ip: '10.8.2.17', trace: 'ff5566778899aabbccddeeff00112277', reason: '例行季度轮换', diff: { fingerprint: ['****55d1', '****a91f'] } },
    { id: 'AU-8831', time: '2026-09-22 15:18:40', operator: 'audit01', name: '审计-周报', action: 'LOGIN', target: 'SESSION / audit01', result: 'SUCCESS', ip: '10.8.9.9', trace: '0066778899aabbccddeeff0011223399', reason: null, diff: null }
  ];

  /* ============ 域 9：平台健康度 ============ */
  var platformHealth = {
    scheduler: { leader: 'scheduler-1', standby: 'scheduler-2（standby）', leaseRemain: 27, lastTickAgo: 1, tickInterval: 1000, tickCount: 812041, avgTickMs: 11, p99TickMs: 143 },
    db: { poolActive: 12, poolIdle: 18, poolMax: 30, slowQueries1m: 0 },
    redis: { connected: true, used: '1.2 GB', max: '4 GB', policy: 'noeviction', queueDepth: 49 },
    nodes: { online: 193, total: 200, rate: 96.5 },
    today: { dispatched: 3842, failed: 17, logIngestGb: 38.2 },
    queues: [
      { name: 'high-priority', waiting: 2, running: 6, max: 6, blocked: false },
      { name: 'default', waiting: 47, running: 3, max: 3, blocked: false },
      { name: 'gpu-queue', waiting: 7, running: 4, max: 4, blocked: true, blockSince: '14:52 起（head_blocked：队头步骤匹配失败 12 次）' },
      { name: 'batch-heavy', waiting: 12, running: 4, max: 4, blocked: false }
    ],
    decisions: [
      { time: '15:32:41', step: 'TASK-…-0042 / 特征计算', decision: 'BLOCKED', reason: 'NO_MATCHING_NODE（8 核 + etl-heavy）' },
      { time: '15:32:40', step: 'TASK-…-0052 / 快照导出', decision: 'DEFERRED', reason: 'QUEUE_FULL batch-heavy 4/4' },
      { time: '15:32:38', step: 'TASK-…-0044 / 库存快照', decision: 'DISPATCHED', reason: '→ prod2-node-011（运行 3/8）' },
      { time: '15:32:35', step: 'TASK-…-0063 / 画像刷新', decision: 'DISPATCHED', reason: '→ prod-node-022（运行 1/8）' },
      { time: '15:32:30', step: 'TASK-…-0061 / 画像刷新', decision: 'SKIPPED', reason: 'CAS 失败（已被他方处理）' }
    ],
    recentAlerts: [
      { time: '15:06', level: 'P1', text: '执行节点离线：prod-node-019 失联超 5 分钟，运行中步骤进入恢复窗口' },
      { time: '06:00', level: 'P2', text: 'TASK-20260925-0022 任务级超时（3h）' },
      { time: '02:41', level: 'P2', text: '互斥锁 orders_rw 持有时长 38m（> 30min 阈值）' }
    ]
  };

  /* 工作台 24h 趋势 */
  var trend24h = {
    success: [42, 38, 45, 51, 48, 40, 36, 33, 30, 28, 26, 31, 44, 62, 78, 91, 84, 76, 82, 88, 95, 102, 97, 88],
    failed:  [2, 1, 0, 1, 0, 0, 1, 0, 0, 2, 1, 0, 1, 2, 1, 0, 3, 1, 0, 2, 1, 1, 0, 1]
  };

  /* ============ 域 10：API 触发（开放接口）============ 真源替换点：GET /openapi/v1/keys */
  var apiKeys = [
    { id: 'AK-0001', name: '风控中台-生产', mask: 'fk_****8c3f', project: 'PRJ-0002',
      scopeWfs: ['WF-0003'], rateLimit: 60, calls24h: 342, lastUsed: '2026-09-25 15:12:03',
      status: 'ENABLED', expireAt: null, creator: 'liu-min', createdAt: '2026-09-10 10:00:00' },
    { id: 'AK-0002', name: '数据平台-开放接口', mask: 'fk_****77b2', project: 'PRJ-0001',
      scopeWfs: ['WF-0001', 'WF-0002'], rateLimit: 120, calls24h: 89, lastUsed: '2026-09-25 14:55:41',
      status: 'ENABLED', expireAt: '2026-12-31', creator: 'pm-ecom', createdAt: '2026-09-12 14:30:00' },
    { id: 'AK-0003', name: '测试联调（已停用）', mask: 'fk_****1d9e', project: 'PRJ-0001',
      scopeWfs: ['*'], rateLimit: 10, calls24h: 0, lastUsed: '2026-09-18 09:02:11',
      status: 'DISABLED', expireAt: null, creator: 'dev-lin', createdAt: '2026-09-15 09:00:00' }
  ];

  var apiCalls = [
    { time: '2026-09-25 15:12:03', key: 'AK-0001', ep: 'POST /openapi/v1/tasks', task: 'TASK-20260925-0110', code: 201, latency: 86, trace: '3a7f22bb19cc40ee99aa55dd8812ee44', note: '创建任务（bizDate=2026-09-25）' },
    { time: '2026-09-25 15:12:04', key: 'AK-0001', ep: 'GET /openapi/v1/tasks/TASK-20260925-0110', task: 'TASK-20260925-0110', code: 200, latency: 23, trace: '3a7f22bb19cc40ee99aa55dd8812ee45', note: '轮询任务详情' },
    { time: '2026-09-25 14:55:41', key: 'AK-0002', ep: 'POST /openapi/v1/tasks/TASK-20260925-0038/stop', task: 'TASK-20260925-0038', code: 202, latency: 41, trace: 'aa8811cc22dd43ee77bb66ff5599aa00', note: '第三方发起终止（STOPPING）' },
    { time: '2026-09-25 14:31:18', key: 'AK-0002', ep: 'POST /openapi/v1/tasks', task: '—', code: 40901, latency: 12, trace: 'bb9922dd33ee44ff66aa77bb88cc00aa', note: '并发策略 FORBID 拒绝（返回 running_task_id）' },
    { time: '2026-09-25 14:30:52', key: 'AK-0002', ep: 'POST /openapi/v1/tasks', task: 'TASK-20260925-0063', code: 201, latency: 79, trace: 'cc8833ee44ff55aa66bb77cc99dd11bb', note: '幂等键命中返回首次响应' },
    { time: '2026-09-25 14:05:33', key: 'AK-0001', ep: 'POST /openapi/v1/tasks/TASK-20260924-0058/retry', task: 'TASK-20260924-0058', code: 202, latency: 38, trace: 'dd7744ff55aa66bb77cc88dd00ee22cc', note: '重跑失败步骤（rerun=failed_steps）' },
    { time: '2026-09-25 13:58:02', key: 'AK-0003', ep: 'POST /openapi/v1/tasks', task: '—', code: 40110, latency: 3, trace: 'ee6655aa66bb77cc88dd99ee11ff33dd', note: 'Key 已停用' },
    { time: '2026-09-25 13:41:47', key: 'AK-0001', ep: 'GET /openapi/v1/tasks?status=FAILED', task: '—', code: 200, latency: 156, trace: 'ff5544bb55cc66dd77ee88ff22aa44ee', note: '列表查询（分页 page_size=20）' }
  ];

  var webhookSubs = [
    { id: 'WH-0001', name: '风控中台回调', url: 'https://risk-gw.corp/api/flowops/callback', urlMask: 'https://risk-gw.corp/****',
      events: ['task.succeeded', 'task.failed', 'task.timeout'], secret: 'whsec_****9d2f', status: 'ENABLED',
      lastDelivery: '2026-09-25 15:14:02', okRate: '98.6%' },
    { id: 'WH-0002', name: '数据平台 IM 机器人', url: 'https://open.feishu.cn/open-apis/bot/v2/hook/****', urlMask: 'https://open.feishu.cn/****',
      events: ['task.failed'], secret: 'whsec_****41b7', status: 'ENABLED',
      lastDelivery: '2026-09-25 06:00:10', okRate: '100%' }
  ];

  var webhookDeliveries = [
    { time: '2026-09-25 15:14:02', event: 'task.succeeded', sub: 'WH-0001', url: 'risk-gw.corp', code: 200, attempt: 1, result: 'SENT', latency: 142 },
    { time: '2026-09-25 06:00:10', event: 'task.failed', sub: 'WH-0002', url: 'open.feishu.cn', code: 200, attempt: 1, result: 'SENT', latency: 205 },
    { time: '2026-09-25 01:12:41', event: 'task.failed', sub: 'WH-0001', url: 'risk-gw.corp', code: 502, attempt: 1, result: 'RETRY', latency: 5000 },
    { time: '2026-09-25 01:13:11', event: 'task.failed', sub: 'WH-0001', url: 'risk-gw.corp', code: 502, attempt: 2, result: 'RETRY', latency: 5000 },
    { time: '2026-09-25 01:18:11', event: 'task.failed', sub: 'WH-0001', url: 'risk-gw.corp', code: 200, attempt: 3, result: 'SENT', latency: 156 },
    { time: '2026-09-24 23:41:15', event: 'step.finished', sub: 'WH-0001', url: 'risk-gw.corp', code: 200, attempt: 1, result: 'SENT', latency: 98 }
  ];

  window.MOCK = {
    TODAY: TODAY,
    roles: roles, PERM_DEMO: PERM_DEMO, SCOPE_DEMO: SCOPE_DEMO,
    projects: projects, clusters: clusters, nodes: nodes, queues: queues,
    credentials: credentials, operators: operators, opVersions: opVersions,
    workflows: workflows, triggers: triggers,
    tasks: tasks, showcase: showcase, backfillBatches: backfillBatches,
    alertRules: alertRules, alertChannels: alertChannels, alertRecords: alertRecords,
    auditLogs: auditLogs, platformHealth: platformHealth, trend24h: trend24h,
    apiKeys: apiKeys, apiCalls: apiCalls, webhookSubs: webhookSubs, webhookDeliveries: webhookDeliveries
  };
})();
