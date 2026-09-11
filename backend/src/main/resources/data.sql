-- 基础模拟数据只负责补齐，不清空预约、会话或工具记录。
-- 需要重新录制 Demo 时，应使用单独的重置功能，而不是随后端启动自动删除。

MERGE INTO users KEY(id) VALUES
('user-001','王阿姨','幸福小区（模拟）','家属开车');

INSERT INTO user_preferences(user_id,auto_speak_enabled,speech_rate,speech_volume,updated_at)
SELECT 'user-001',FALSE,0.9,1.0,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM user_preferences WHERE user_id='user-001');

MERGE INTO hospitals (id,name,address,hospital_level,description,specialty_tags,elderly_services,enabled) KEY(id) VALUES
('h001','市第一医院（模拟）','健康路1号（模拟）','三级甲等','以心血管、神经系统疾病和老年慢病复诊服务为特色。','心血管,神经内科,老年慢病管理','老年服务窗口,轮椅通道,志愿者引导',TRUE),
('h002','市人民医院（模拟）','人民路88号（模拟）','三级甲等','提供内分泌、骨科和常见慢性病复诊服务。','内分泌,骨科,慢性病随访','无障碍电梯,人工挂号窗口,家属等候区',TRUE);

MERGE INTO departments (id,hospital_id,name,description,specialty_tags,followup_scope,location,enabled) KEY(id) VALUES
('d001','h001','心内科','提供心血管慢性疾病复诊和随访服务。','高血压,冠心病,心律失常','已由医生安排的心血管相关复诊','门诊楼三层',TRUE),
('d002','h001','神经内科','提供神经系统疾病复诊和康复随访服务。','脑血管病,头痛,神经康复','已由医生安排的神经内科复诊','门诊楼四层',TRUE),
('d003','h002','内分泌科','提供内分泌慢性疾病复诊和指标随访服务。','糖尿病,甲状腺疾病,代谢管理','已由医生安排的内分泌科复诊','门诊楼二层',TRUE),
('d004','h002','骨科','提供骨科术后和慢性骨关节疾病复诊服务。','关节,脊柱,术后复查','已由医生安排的骨科复诊','门诊楼五层',TRUE),
('d005','h001','内分泌科','提供糖尿病和甲状腺疾病的常规复诊服务。','糖尿病,甲状腺疾病','已由医生安排的内分泌科复诊','门诊楼三层',TRUE),
('d006','h002','心内科','提供常见心血管慢性疾病的复诊随访服务。','高血压,冠心病','已由医生安排的心内科复诊','门诊楼四层',TRUE);

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

MERGE INTO material_templates KEY(id) VALUES
('m001','通用','身份证',TRUE,1),
('m002','通用','医保卡或电子医保凭证',TRUE,2),
('m003','通用','上次就诊病历',TRUE,3),
('m004','通用','既往检查报告',TRUE,4),
('m005','通用','正在使用的药物清单',FALSE,5),
('m006','心内科','血压和心率记录',FALSE,10),
('m007','内分泌科','近期血糖记录',FALSE,10),
('m008','内分泌科','最近一次化验单',FALSE,11);

MERGE INTO travel_routes KEY(id) VALUES
('route-001','幸福小区（模拟）','健康路1号（模拟）','家属开车',30),
('route-002','幸福小区（模拟）','健康路1号（模拟）','打车',35),
('route-003','幸福小区（模拟）','健康路1号（模拟）','公交',50),
('route-004','幸福小区（模拟）','人民路88号（模拟）','家属开车',25),
('route-005','幸福小区（模拟）','人民路88号（模拟）','打车',30),
('route-006','幸福小区（模拟）','人民路88号（模拟）','公交',45);
