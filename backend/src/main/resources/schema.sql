CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(40) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  home_address VARCHAR(200),
  preferred_transport VARCHAR(40)
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS home_longitude DOUBLE PRECISION;
ALTER TABLE users ADD COLUMN IF NOT EXISTS home_latitude DOUBLE PRECISION;

CREATE TABLE IF NOT EXISTS hospitals (
  id VARCHAR(40) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  address VARCHAR(200) NOT NULL
);

ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS hospital_level VARCHAR(40);
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS description VARCHAR(500);
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS specialty_tags VARCHAR(300);
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS elderly_services VARCHAR(300);
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS main_entrance VARCHAR(100);

CREATE TABLE IF NOT EXISTS departments (
  id VARCHAR(40) PRIMARY KEY,
  hospital_id VARCHAR(40) NOT NULL,
  name VARCHAR(60) NOT NULL
);

ALTER TABLE departments ADD COLUMN IF NOT EXISTS description VARCHAR(500);
ALTER TABLE departments ADD COLUMN IF NOT EXISTS specialty_tags VARCHAR(300);
ALTER TABLE departments ADD COLUMN IF NOT EXISTS followup_scope VARCHAR(300);
ALTER TABLE departments ADD COLUMN IF NOT EXISTS location VARCHAR(100);
ALTER TABLE departments ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE NOT NULL;

-- 出诊医生。一个科室多名医生，号源挂在医生身上（见 appointment_slots.doctor_id）。
-- slot_type 是这位医生出诊时放的号别，**手工指定**，不由职称推导——职称只用于展示。
-- 之所以把号别也留在医生行上，是因为现阶段没有排班表，种子里「谁放什么号」只能写在医生上；
-- 号源行自己另存一份 slot_type（生成时拷过去），将来要「同一位医生既出普通号又出专家号」，
-- 只需加一张排班表覆盖号源那一列，这里的列不用动。
-- **排班不存在这张表上**：每个科室每周放哪 3 天、一天 4 格各排谁坐诊，全部由
-- RollingAppointmentSlotInitializer.openWeekdays / expertAt / normalAt 按
-- 「科室 + 日期」算出来（纯函数、确定性，周末一律不放）。所以这里没有出诊星期列——
-- 「第几个放号日坐哪一格」本来也不是一个「星期集合」能表达的。
CREATE TABLE IF NOT EXISTS doctors (
  id              VARCHAR(40)  PRIMARY KEY,
  hospital_id     VARCHAR(40)  NOT NULL,
  department_id   VARCHAR(40)  NOT NULL,
  name            VARCHAR(40)  NOT NULL,
  title           VARCHAR(40)  NOT NULL,
  good_at         VARCHAR(300),
  introduction    VARCHAR(300),
  slot_type       VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
  enabled         BOOLEAN DEFAULT TRUE NOT NULL
);

ALTER TABLE doctors ADD COLUMN IF NOT EXISTS slot_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
-- 排班搬进生成器、按「科室 + 日期」算之后，出诊星期这一列不再生效。老库上留着它只会让人
-- 以为它还在管事，所以直接删掉（schema.sql 每次启动都跑，删不到也不会报错）。
ALTER TABLE doctors DROP COLUMN IF EXISTS clinic_weekdays;

CREATE TABLE IF NOT EXISTS clinic_locations (
  id VARCHAR(40) PRIMARY KEY,
  hospital_id VARCHAR(40) NOT NULL,
  department_id VARCHAR(40) NOT NULL,
  building_name VARCHAR(100) NOT NULL,
  entrance_name VARCHAR(100) NOT NULL,
  floor_name VARCHAR(40) NOT NULL,
  room_name VARCHAR(60) NOT NULL,
  check_in_point VARCHAR(120) NOT NULL,
  landmark VARCHAR(200),
  accessible_route_hint VARCHAR(500),
  help_desk VARCHAR(120),
  verified_at TIMESTAMP NOT NULL,
  enabled BOOLEAN DEFAULT TRUE NOT NULL
);

CREATE TABLE IF NOT EXISTS appointment_slots (
  id VARCHAR(40) PRIMARY KEY,
  hospital_id VARCHAR(40) NOT NULL,
  hospital_name VARCHAR(100) NOT NULL,
  department VARCHAR(60) NOT NULL,
  appointment_date DATE NOT NULL,
  appointment_time TIME NOT NULL,
  available BOOLEAN NOT NULL
);

ALTER TABLE appointment_slots ADD COLUMN IF NOT EXISTS clinic_location_id VARCHAR(40);

-- 号源现在多一维「医生」。同一天同一时段，不同医生各有一条号源（id 里带医生 id，见
-- RollingAppointmentSlotInitializer.slotId）。四条一起加：
--   doctor_id     出诊医生；旧号源上没有这一列的值（NULL），本次由生成器整批重建，不做回填
--   slot_type     本条件号源放的是普通号还是专家号：NORMAL=普通号 / EXPERT=专家号
--   department_id 科室外键。原来只有 department（科室**名字**），改个名字关联就断
--   fee_cents     挂号费，**以分为单位**（整数存储，避免浮点误差）；普通号与专家号不同价
ALTER TABLE appointment_slots ADD COLUMN IF NOT EXISTS doctor_id     VARCHAR(40);
ALTER TABLE appointment_slots ADD COLUMN IF NOT EXISTS slot_type     VARCHAR(20);
ALTER TABLE appointment_slots ADD COLUMN IF NOT EXISTS department_id VARCHAR(40);
ALTER TABLE appointment_slots ADD COLUMN IF NOT EXISTS fee_cents     INT;

-- 号源名额：一条号源 = 一位医生在一个时刻里的一班，capacity 是这一班能接几位，booked 是已经占了几位。
-- 旧模型「一条号源 = 一个号」用 available 布尔表达；现在一条号源可以接多位，于是 available
-- **降级为派生位**（available = booked < capacity），只保留给现有那 4 处查询继续用——
-- 查询侧一行都不用改，这是「只改数据库、不动助手交互」能成立的前提。
-- 默认 1 是刻意的：旧库上的存量行、以及测试里手工 INSERT 的号源，都自动等价于旧模型，
-- 不会因为「新列没填」而变得不可约。不变式：0 <= booked <= capacity。
ALTER TABLE appointment_slots ADD COLUMN IF NOT EXISTS capacity INT NOT NULL DEFAULT 1;
ALTER TABLE appointment_slots ADD COLUMN IF NOT EXISTS booked   INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS user_schedules (
  id VARCHAR(40) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  title VARCHAR(120) NOT NULL,
  start_at TIMESTAMP NOT NULL,
  end_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS family_contacts (
  id VARCHAR(40) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  name VARCHAR(40) NOT NULL,
  relationship VARCHAR(40) NOT NULL,
  phone VARCHAR(30) NOT NULL
);

CREATE TABLE IF NOT EXISTS appointments (
  id VARCHAR(50) PRIMARY KEY,
  slot_id VARCHAR(40) NOT NULL,
  user_id VARCHAR(40) NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS departure_time TIMESTAMP;
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS transport VARCHAR(40);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS reminder_status VARCHAR(100);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS family_status VARCHAR(100);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS materials CLOB;

-- 预约上存一份**医生快照**，而不是每次去关联号源查：号源是滚动重建的（周末号源会删、
-- 过去的日期会过期），预约记录必须自己说得清「当时约的是谁」。
-- 与 reminder_status / family_status / materials 的做法一致——结果写死在预约行上。
-- 旧预约没有这几列的值（NULL），前端如实显示「医生信息未记录」，不替它编一位医生。
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS doctor_name  VARCHAR(40);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS doctor_title VARCHAR(40);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS slot_type    VARCHAR(20);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS fee_cents    INT;

CREATE TABLE IF NOT EXISTS material_templates (
  id VARCHAR(50) PRIMARY KEY,
  department VARCHAR(60) NOT NULL,
  material_name VARCHAR(120) NOT NULL,
  required BOOLEAN NOT NULL,
  sort_order INT NOT NULL
);

CREATE TABLE IF NOT EXISTS care_guide_articles (
  id VARCHAR(50) PRIMARY KEY,
  topic VARCHAR(50) NOT NULL,
  title VARCHAR(120) NOT NULL,
  content CLOB NOT NULL,
  keywords VARCHAR(300) NOT NULL,
  hospital_name VARCHAR(100),
  department VARCHAR(60),
  sort_order INT NOT NULL,
  enabled BOOLEAN DEFAULT TRUE NOT NULL
);

CREATE TABLE IF NOT EXISTS appointment_materials (
  id VARCHAR(50) PRIMARY KEY,
  appointment_id VARCHAR(50) NOT NULL,
  material_code VARCHAR(50) NOT NULL,
  material_name VARCHAR(120) NOT NULL,
  required BOOLEAN NOT NULL,
  status VARCHAR(30) NOT NULL,
  confirm_source VARCHAR(30),
  photo_url VARCHAR(500),
  updated_at TIMESTAMP NOT NULL,
  UNIQUE (appointment_id, material_code)
);

CREATE TABLE IF NOT EXISTS user_preferences (
  user_id VARCHAR(40) PRIMARY KEY,
  auto_speak_enabled BOOLEAN NOT NULL,
  speech_rate DOUBLE PRECISION NOT NULL,
  speech_volume DOUBLE PRECISION NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS travel_routes (
  id VARCHAR(50) PRIMARY KEY,
  origin VARCHAR(200) NOT NULL,
  destination VARCHAR(200) NOT NULL,
  transport VARCHAR(40) NOT NULL,
  duration_minutes INT NOT NULL
);

ALTER TABLE travel_routes ADD COLUMN IF NOT EXISTS distance_meters INT;
ALTER TABLE travel_routes ADD COLUMN IF NOT EXISTS route_steps CLOB;
ALTER TABLE travel_routes ADD COLUMN IF NOT EXISTS polyline CLOB;
ALTER TABLE travel_routes ADD COLUMN IF NOT EXISTS route_source VARCHAR(30) DEFAULT 'SIMULATED';

CREATE TABLE IF NOT EXISTS reminders (
  id VARCHAR(50) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  title VARCHAR(120) NOT NULL,
  remind_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS family_notifications (
  id VARCHAR(50) PRIMARY KEY,
  contact_id VARCHAR(40) NOT NULL,
  content VARCHAR(500) NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation_sessions (
  id VARCHAR(50) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  stage VARCHAR(60) NOT NULL,
  state_json CLOB NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS last_response_json CLOB;

-- 会话生命周期状态：ACTIVE=进行中，CLOSED=用户主动结束，EXPIRED=太久没说话自动收尾。
-- 历史数据这一列为 NULL，一律按 ACTIVE 处理，所以这个改动对已有会话是安全的。
-- 「多久算太久」由配置决定，判定时用 updated_at 现算，不另存过期时间：
-- 多存一列就多一处可能和真实活动时间不一致的地方。
ALTER TABLE conversation_sessions ADD COLUMN IF NOT EXISTS status VARCHAR(20);

CREATE TABLE IF NOT EXISTS conversation_messages (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  conversation_id VARCHAR(50) NOT NULL,
  role VARCHAR(20) NOT NULL,
  content CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);

-- 图片 / 语音这类带附件的一轮：message_type 区分文本与附件轮，attachment_id 指向附件表。
-- 都允许为空，旧的纯文字消息不受影响。
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS message_type VARCHAR(20);
ALTER TABLE conversation_messages ADD COLUMN IF NOT EXISTS attachment_id BIGINT;

-- 附件本体（拍照图片、材料照片）。刻意与 conversation_messages 分表：
-- base64 data URL 动辄几十万字符，混进消息表会让 recentMessages 每轮都被它灌爆。
CREATE TABLE IF NOT EXISTS conversation_attachments (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  conversation_id VARCHAR(50) NOT NULL,
  message_id BIGINT,
  kind VARCHAR(30) NOT NULL,
  data_url CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);

-- 一次识图的结论。只存文字，不存图片本体；图片本体在 conversation_attachments。
-- keyFacts 折进 description 一起存，避免为一个展示用字段再加一列。
CREATE TABLE IF NOT EXISTS vision_results (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  attachment_id BIGINT,
  conversation_id VARCHAR(50) NOT NULL,
  hint VARCHAR(300),
  description CLOB,
  ocr CLOB,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS tool_call_logs (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  conversation_id VARCHAR(50) NOT NULL,
  tool_name VARCHAR(80) NOT NULL,
  request_json CLOB NOT NULL,
  response_json CLOB NOT NULL,
  success BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL
);

ALTER TABLE tool_call_logs ADD COLUMN IF NOT EXISTS response_json CLOB;

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(50);
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(50);
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS appointment_id VARCHAR(50);
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS status VARCHAR(30) DEFAULT 'CREATED';
ALTER TABLE family_notifications ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(50);

-- 协同照护端：家属/志愿者与其协同的就诊人关系（一对多）
CREATE TABLE IF NOT EXISTS care_relations (
  id VARCHAR(40) PRIMARY KEY,
  caregiver_id VARCHAR(40) NOT NULL,
  elder_user_id VARCHAR(40) NOT NULL,
  role VARCHAR(20) NOT NULL,
  relationship VARCHAR(40)
);

-- 家属/志愿者代约：标记这份预约由哪位照护者安排（null=老人自己或助手约的）
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS arranged_by VARCHAR(40);

-- 陪同本次复诊的照护者 id（null=未确认陪同或老人自约）；照护端据此决定是否显示出发建议
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS accompanied_by VARCHAR(40);

-- 协同照护端定向通知：代约结果、老人改动、途中求助、紧急等，发给指定的照护者账号
CREATE TABLE IF NOT EXISTS care_notifications (
  id VARCHAR(50) PRIMARY KEY,
  caregiver_id VARCHAR(40) NOT NULL,
  elder_user_id VARCHAR(40) NOT NULL,
  content VARCHAR(500) NOT NULL,
  kind VARCHAR(20) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

-- 老人端“健康备忘”：复诊助手中老人托付的健康/复诊相关小记；status ACTIVE=进行中 / DONE=已完成 / DELETED=已删除
CREATE TABLE IF NOT EXISTS memos (
  id VARCHAR(50) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  text VARCHAR(300) NOT NULL,
  remind_at TIMESTAMP,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

-- 重复提醒规则：null=只提醒一次；DAILY=每天 / WEEKLY=每周 / MONTHLY=每月。
-- remind_at 存“下一次到点”的日期时间，同时充当星期几（WEEKLY）/几号（MONTHLY）的锚点。
ALTER TABLE memos ADD COLUMN IF NOT EXISTS repeat_rule VARCHAR(20);

-- 老人上报的实测数值（血压/血糖/心率…）：和 memos（“要做的事”，带提醒、可完成可删除）分家，
-- 这里记的是“已经量到的数”，只增不删，用来回查最近几次。有数值记 value_num，只有说法（“有点高”）记 value_text。
CREATE TABLE IF NOT EXISTS health_records (
  id VARCHAR(50) PRIMARY KEY,
  user_id VARCHAR(40) NOT NULL,
  item VARCHAR(40) NOT NULL,
  value_num DECIMAL(8,2),
  value_text VARCHAR(60) NOT NULL,
  unit VARCHAR(20) NOT NULL,
  raw_text VARCHAR(200),
  conversation_id VARCHAR(50),
  recorded_at TIMESTAMP NOT NULL
);

-- 长期记忆：助手跨对话记住的关于这位老人的事（常去的医院、科室、习惯的时段）。
-- 主键是 (user_id, memory_key) 而不是自增 id：同一个 key 只留最新的一版——
-- 「常去的医院」是会变的，换一家医院应该覆盖旧的，而不是越攒越多让提示词被旧偏好污染。
CREATE TABLE IF NOT EXISTS user_memories (
  user_id VARCHAR(40) NOT NULL,
  memory_key VARCHAR(60) NOT NULL,
  kind VARCHAR(20) NOT NULL,
  content VARCHAR(300) NOT NULL,
  source VARCHAR(30) NOT NULL,
  source_conversation_id VARCHAR(50),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (user_id, memory_key)
);
