-- 基础模拟数据只负责补齐，不清空预约、会话或工具记录。
-- 需要重新录制 Demo 时，应使用单独的重置功能，而不是随后端启动自动删除。

-- 必须写明列名：users 还有 home_longitude / home_latitude 两列（见 schema.sql 的 ALTER），
-- 不写列名的话这里的 4 个值对不上整表列数，MERGE 会直接报错。
MERGE INTO users (id,name,home_address,preferred_transport) KEY(id) VALUES
('user-001','王阿姨','幸福小区（模拟）','家属开车'),
('user-f001','小丽',NULL,NULL),
('user-v001','李阿姨',NULL,NULL),
('user-002','张伯伯','幸福小区（模拟）','公交');

UPDATE users SET home_longitude=116.365300,home_latitude=39.921900 WHERE id='user-001';

INSERT INTO user_preferences(user_id,auto_speak_enabled,speech_rate,speech_volume,updated_at)
SELECT 'user-001',FALSE,0.9,1.0,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM user_preferences WHERE user_id='user-001');

MERGE INTO hospitals (id,name,address,hospital_level,description,specialty_tags,elderly_services,enabled) KEY(id) VALUES
('h001','市第一医院（模拟）','健康路1号（模拟）','三级甲等','以心血管、神经系统疾病和老年慢病复诊服务为特色。','心血管,神经内科,老年慢病管理','老年服务窗口,轮椅通道,志愿者引导',TRUE),
('h002','市人民医院（模拟）','人民路88号（模拟）','三级甲等','提供内分泌、骨科和常见慢性病复诊服务。','内分泌,骨科,慢性病随访','无障碍电梯,人工挂号窗口,家属等候区',TRUE);

UPDATE hospitals SET longitude=116.397428,latitude=39.909230,main_entrance='门诊楼南门' WHERE id='h001';
UPDATE hospitals SET longitude=116.430200,latitude=39.905600,main_entrance='门诊楼东门' WHERE id='h002';

MERGE INTO departments (id,hospital_id,name,description,specialty_tags,followup_scope,location,enabled) KEY(id) VALUES
('d001','h001','心内科','提供心血管慢性疾病复诊和随访服务。','高血压,冠心病,心律失常','已由医生安排的心血管相关复诊','门诊楼三层',TRUE),
('d002','h001','神经内科','提供神经系统疾病复诊和康复随访服务。','脑血管病,头痛,神经康复','已由医生安排的神经内科复诊','门诊楼四层',TRUE),
('d003','h002','内分泌科','提供内分泌慢性疾病复诊和指标随访服务。','糖尿病,甲状腺疾病,代谢管理','已由医生安排的内分泌科复诊','门诊楼二层',TRUE),
('d004','h002','骨科','提供骨科术后和慢性骨关节疾病复诊服务。','关节,脊柱,术后复查','已由医生安排的骨科复诊','门诊楼五层',TRUE),
('d005','h001','内分泌科','提供糖尿病和甲状腺疾病的常规复诊服务。','糖尿病,甲状腺疾病','已由医生安排的内分泌科复诊','门诊楼三层',TRUE),
('d006','h002','心内科','提供常见心血管慢性疾病的复诊随访服务。','高血压,冠心病','已由医生安排的心内科复诊','门诊楼四层',TRUE);

MERGE INTO clinic_locations (id,hospital_id,department_id,building_name,entrance_name,floor_name,room_name,check_in_point,landmark,accessible_route_hint,help_desk,verified_at,enabled) KEY(id) VALUES
('loc-d001','h001','d001','门诊楼','南门','三层','308诊室','门诊楼一层自助机或人工窗口','出电梯后向右，经过心电检查区','从南门进入，沿无障碍通道到一层大厅，乘无障碍电梯到三层，出电梯后向右前往308诊室。','三层心内科护士站',CURRENT_TIMESTAMP,TRUE),
('loc-d002','h001','d002','门诊楼','南门','四层','412诊室','门诊楼一层自助机或人工窗口','出电梯后沿蓝色神经内科标识前行','从南门进入，沿无障碍通道到一层大厅，乘无障碍电梯到四层，按蓝色标识前往412诊室。','四层神经内科护士站',CURRENT_TIMESTAMP,TRUE),
('loc-d003','h002','d003','门诊楼','东门','二层','216诊室','门诊楼一层服务台旁报到机','二层电梯口左侧','从东门进入后向服务台确认，乘无障碍电梯到二层，出电梯左转前往216诊室。','二层内分泌科护士站',CURRENT_TIMESTAMP,TRUE),
('loc-d004','h002','d004','门诊楼','东门','五层','506诊室','门诊楼一层服务台旁报到机','五层康复训练区对面','从东门进入后沿无障碍通道前往电梯，乘梯到五层，按骨科标识前往506诊室。','五层骨科护士站',CURRENT_TIMESTAMP,TRUE),
('loc-d005','h001','d005','门诊楼','南门','三层','316诊室','门诊楼一层自助机或人工窗口','三层采血区旁','从南门进入，沿无障碍通道到一层大厅，乘无障碍电梯到三层，按内分泌科标识前往316诊室。','三层内分泌科护士站',CURRENT_TIMESTAMP,TRUE),
('loc-d006','h002','d006','门诊楼','东门','四层','408诊室','门诊楼一层服务台旁报到机','四层候诊区内侧','从东门进入后沿无障碍通道前往电梯，乘梯到四层，按心内科标识前往408诊室。','四层心内科护士站',CURRENT_TIMESTAMP,TRUE);

INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0918-0900','h001','市第一医院（模拟）','心内科','2026-09-18','09:00:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0918-0900');
INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0918-1020','h001','市第一医院（模拟）','心内科','2026-09-18','10:20:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0918-1020');
INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0918-1430','h001','市第一医院（模拟）','心内科','2026-09-18','14:30:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0918-1430');
INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0918-1600','h001','市第一医院（模拟）','心内科','2026-09-18','16:00:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0918-1600');
INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0917-0900','h001','市第一医院（模拟）','心内科','2026-09-17','09:00:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0917-0900');
INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0919-1430','h001','市第一医院（模拟）','心内科','2026-09-19','14:30:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0919-1430');

INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0920-0900','h002','市人民医院（模拟）','内分泌科','2026-09-20','09:00:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0920-0900');
INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0920-1030','h002','市人民医院（模拟）','内分泌科','2026-09-20','10:30:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0920-1030');
INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0920-1400','h002','市人民医院（模拟）','内分泌科','2026-09-20','14:00:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0920-1400');
INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0920-1500','h002','市人民医院（模拟）','内分泌科','2026-09-20','15:00:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0920-1500');
INSERT INTO appointment_slots(id,hospital_id,hospital_name,department,appointment_date,appointment_time,available)
SELECT 'slot-0921-0930','h002','市人民医院（模拟）','内分泌科','2026-09-21','09:30:00',TRUE
WHERE NOT EXISTS (SELECT 1 FROM appointment_slots WHERE id='slot-0921-0930');

MERGE INTO user_schedules KEY(id) VALUES
('schedule-001','user-001','社区体检','2026-09-18 10:00:00','2026-09-18 11:00:00'),
('schedule-002','user-001','和家人吃饭','2026-09-20 12:00:00','2026-09-20 13:30:00');

MERGE INTO family_contacts KEY(id) VALUES
('family-001','user-001','小丽','女儿','13800001234');

MERGE INTO care_relations KEY(id) VALUES
('rel-f001','user-f001','user-001','FAMILY','女儿'),
('rel-v001','user-v001','user-001','VOLUNTEER','社区志愿者'),
('rel-v002','user-v001','user-002','VOLUNTEER','社区志愿者');

MERGE INTO material_templates KEY(id) VALUES
('m001','通用','身份证',TRUE,1),
('m002','通用','医保卡或电子医保凭证',TRUE,2),
('m003','通用','上次就诊病历',TRUE,3),
('m004','通用','既往检查报告',TRUE,4),
('m005','通用','正在使用的药物清单',FALSE,5),
('m006','心内科','血压和心率记录',FALSE,10),
('m007','内分泌科','近期血糖记录',FALSE,10),
('m008','内分泌科','最近一次化验单',FALSE,11);

MERGE INTO care_guide_articles KEY(id) VALUES
('guide-process','PROCESS','复诊预约的一般流程',
 '先确认医生要求复诊的医院、科室和日期，再查询模拟号源并选择时间。提交预约、创建提醒和通知家属前都要由本人明确确认。预约完成后按事项卡核对时间、材料和出发建议。',
 '复诊,预约,办理,流程,怎么做',NULL,NULL,1,TRUE),
('guide-arrival','ARRIVAL','到院后的办理步骤',
 '到院后可先查看预约凭证，按照医院指引报到、候诊。找不到入口时，可以向门诊服务台、志愿者服务点或对应科室护士站询问。具体安排以医院现场通知为准。',
 '到院,报到,候诊,护士,服务台,咨询',NULL,NULL,2,TRUE),
('guide-help','HELP','不清楚时可以向谁求助',
 '预约和到院流程不清楚时，可以咨询医院预约服务、门诊服务台或护士站；涉及病情、检查结果和用药的问题，应咨询医生或专业医疗机构。紧急不适应及时联系急救服务或寻求线下帮助。',
 '不懂,不会,咨询,医生,护士,人工,帮助',NULL,NULL,3,TRUE),
('guide-change','CHANGE','改期、取消和迟到处理',
 '需要改期或取消已确认预约时，应先查询本人预约并再次确认，系统不会根据一句模糊表达直接执行。可能迟到时，请尽快联系医院确认是否还能报到。',
 '改期,取消,退掉,撤销,迟到,预约',NULL,NULL,4,TRUE);

UPDATE appointment_slots SET clinic_location_id=CASE
  WHEN hospital_id='h001' AND department='心内科' THEN 'loc-d001'
  WHEN hospital_id='h001' AND department='神经内科' THEN 'loc-d002'
  WHEN hospital_id='h001' AND department='内分泌科' THEN 'loc-d005'
  WHEN hospital_id='h002' AND department='内分泌科' THEN 'loc-d003'
  WHEN hospital_id='h002' AND department='骨科' THEN 'loc-d004'
  WHEN hospital_id='h002' AND department='心内科' THEN 'loc-d006'
  ELSE clinic_location_id END;

MERGE INTO travel_routes (id,origin,destination,transport,duration_minutes,distance_meters,route_steps,polyline,route_source) KEY(id) VALUES
('route-001','幸福小区（模拟）','健康路1号（模拟）','家属开车',30,8700,'从幸福小区东门出发|沿幸福路向南行驶|在健康路口左转|从市第一医院门诊楼南门进入','116.365300,39.921900;116.374800,39.918300;116.386500,39.913600;116.397428,39.909230','SIMULATED'),
('route-002','幸福小区（模拟）','健康路1号（模拟）','打车',35,9100,'在幸福小区东门上车|沿幸福路和健康路行驶|在市第一医院门诊楼南门下车','116.365300,39.921900;116.377200,39.919100;116.389300,39.912800;116.397428,39.909230','SIMULATED'),
('route-003','幸福小区（模拟）','健康路1号（模拟）','公交',50,10200,'步行约6分钟到幸福小区公交站|乘坐12路公交车|在市第一医院南门站下车|步行约3分钟到门诊楼南门','116.365300,39.921900;116.370600,39.919900;116.383100,39.914600;116.397428,39.909230','SIMULATED'),
('route-004','幸福小区（模拟）','人民路88号（模拟）','家属开车',25,7600,'从幸福小区东门出发|沿人民路向东行驶|从市人民医院门诊楼东门进入','116.365300,39.921900;116.389900,39.916500;116.412600,39.909800;116.430200,39.905600','SIMULATED'),
('route-005','幸福小区（模拟）','人民路88号（模拟）','打车',30,8000,'在幸福小区东门上车|沿人民路行驶|在市人民医院门诊楼东门下车','116.365300,39.921900;116.392600,39.915200;116.416800,39.908700;116.430200,39.905600','SIMULATED'),
('route-006','幸福小区（模拟）','人民路88号（模拟）','公交',45,9400,'步行约5分钟到幸福小区公交站|乘坐8路公交车|在市人民医院东门站下车|步行约4分钟到门诊楼东门','116.365300,39.921900;116.378600,39.918700;116.405500,39.911200;116.430200,39.905600','SIMULATED');
